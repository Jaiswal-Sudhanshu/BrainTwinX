package com.braintwinx.service;

import com.braintwinx.audit.AuditService;
import com.braintwinx.client.AiClassificationResponse;
import com.braintwinx.client.AiServiceClient;
import com.braintwinx.dto.AnalysisJobResponse;
import com.braintwinx.dto.PredictionResponse;
import com.braintwinx.dto.ScanStatusResponse;
import com.braintwinx.entity.AnalysisJob;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.ModelStatus;
import com.braintwinx.entity.ModelType;
import com.braintwinx.entity.ModelVersion;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.Prediction;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.ScanStatus;
import com.braintwinx.entity.User;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import com.braintwinx.exception.ResourceNotFoundException;
import com.braintwinx.mapper.AnalysisJobMapper;
import com.braintwinx.mapper.PredictionMapper;
import com.braintwinx.repository.AnalysisJobRepository;
import com.braintwinx.repository.ModelVersionRepository;
import com.braintwinx.repository.PredictionRepository;
import com.braintwinx.repository.ScanRepository;
import com.braintwinx.repository.UserRepository;
import com.braintwinx.security.JwtService;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates brain tumor classification inference.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Caseload scoping (ADR-007) — doctors can only analyse scans belonging to their patients.</li>
 *   <li>Idempotency (brief section 21) — duplicate analysis requests resolve to the existing job.</li>
 *   <li>State machine integrity — scans progress legally: VALIDATING → VALIDATED → QUEUED → PROCESSING → COMPLETED.</li>
 *   <li>Fail-closed integrity — if weights or service are unavailable, the job fails with a typed error and NO prediction row is written.</li>
 * </ul>
 */
@Service
public class ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);
    private static final String RESOURCE_TYPE = "Scan";

    private final ScanRepository scanRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final PredictionRepository predictionRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final AiServiceClient aiServiceClient;
    private final AuditService auditService;
    private final AnalysisJobMapper analysisJobMapper;
    private final PredictionMapper predictionMapper;
    private final ObjectMapper objectMapper;

    public ClassificationService(ScanRepository scanRepository,
                                 AnalysisJobRepository analysisJobRepository,
                                 PredictionRepository predictionRepository,
                                 ModelVersionRepository modelVersionRepository,
                                 UserRepository userRepository,
                                 StorageService storageService,
                                 AiServiceClient aiServiceClient,
                                 AuditService auditService,
                                 AnalysisJobMapper analysisJobMapper,
                                 PredictionMapper predictionMapper,
                                 ObjectMapper objectMapper) {
        this.scanRepository = scanRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.predictionRepository = predictionRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.aiServiceClient = aiServiceClient;
        this.auditService = auditService;
        this.analysisJobMapper = analysisJobMapper;
        this.predictionMapper = predictionMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Triggers asynchronous classification analysis for a scan.
     *
     * @param scanPublicId public ID of the scan
     * @param idempotencyKey client-supplied key to prevent duplicated runs
     * @param principal authenticated user
     * @param httpRequest HTTP servlet request
     * @return {@link AnalysisJobResponse} public job representation
     */
    @Transactional
    public AnalysisJobResponse triggerAnalysis(String scanPublicId,
                                              String idempotencyKey,
                                              JwtService.AuthenticatedPrincipal principal,
                                              HttpServletRequest httpRequest) {
        User caller = requireCaller(principal);
        requireWriteAccess(caller);

        Scan scan = loadScanInScope(scanPublicId, caller);

        if (!scan.getPatient().isActive()) {
            throw new ApiException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot analyse scan for archived patient: " + scan.getPatient().getPatientCode());
        }

        String effectiveKey = (idempotencyKey == null || idempotencyKey.isBlank())
                ? UUID.randomUUID().toString()
                : idempotencyKey.trim();

        // Check if an existing job for (scan, idempotencyKey) already exists
        var existingJob = analysisJobRepository.findByScanAndIdempotencyKey(scan, effectiveKey);
        if (existingJob.isPresent()) {
            log.info("Idempotency hit: returning existing analysis job {} for scan {}",
                    existingJob.get().getPublicId(), scanPublicId);
            return analysisJobMapper.toResponse(existingJob.get());
        }

        // Advance scan to QUEUED per state machine rules
        prepareScanForQueue(scan);

        AnalysisJob job = new AnalysisJob(scan, effectiveKey, caller, Instant.now());
        AnalysisJob savedJob = analysisJobRepository.save(job);

        auditService.record(caller, caller.getUsername(), AuditAction.ANALYSIS_STARTED,
                RESOURCE_TYPE, scan.getPublicId(), true,
                Map.of("jobId", savedJob.getPublicId(), "idempotencyKey", effectiveKey), httpRequest);

        // Execute asynchronous inference
        executeJobAsync(savedJob.getId());

        return analysisJobMapper.toResponse(savedJob);
    }

    /**
     * Performs synchronous classification for a scan (used by direct API or synchronous testing).
     */
    @Transactional
    public PredictionResponse classifyScanDirect(String scanPublicId,
                                                JwtService.AuthenticatedPrincipal principal,
                                                HttpServletRequest httpRequest) {
        User caller = requireCaller(principal);
        requireWriteAccess(caller);

        Scan scan = loadScanInScope(scanPublicId, caller);

        if (!scan.getPatient().isActive()) {
            throw new ApiException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot analyse scan for archived patient: " + scan.getPatient().getPatientCode());
        }

        prepareScanForQueue(scan);

        String idempotencyKey = UUID.randomUUID().toString();
        AnalysisJob job = new AnalysisJob(scan, idempotencyKey, caller, Instant.now());
        AnalysisJob savedJob = analysisJobRepository.save(job);

        auditService.record(caller, caller.getUsername(), AuditAction.ANALYSIS_STARTED,
                RESOURCE_TYPE, scan.getPublicId(), true,
                Map.of("jobId", savedJob.getPublicId(), "idempotencyKey", idempotencyKey), httpRequest);

        Prediction prediction = runInferencePipeline(savedJob.getId());
        return predictionMapper.toResponse(prediction);
    }

    @Async
    public void executeJobAsync(Long jobId) {
        try {
            runInferencePipeline(jobId);
        } catch (Exception e) {
            log.error("Asynchronous analysis job execution failed for job id {}", jobId, e);
        }
    }

    /**
     * Core execution pipeline for an analysis job.
     */
    @Transactional
    public Prediction runInferencePipeline(Long jobId) {
        AnalysisJob job = analysisJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Analysis job not found: " + jobId));
        Scan scan = job.getScan();

        try {
            job.start(Instant.now());
            job.updateProgress(10);
            if (scan.getStatus().canTransitionTo(ScanStatus.PROCESSING)) {
                scan.transitionTo(ScanStatus.PROCESSING);
            }
            analysisJobRepository.save(job);
            scanRepository.save(scan);

            // 1. Verify AI service is ready (fails closed if weights missing)
            aiServiceClient.ensureReady();
            job.updateProgress(30);

            // 2. Load MRI image bytes from storage
            byte[] imageBytes;
            try (InputStream is = storageService.load(scan.getStorageKey())) {
                imageBytes = is.readAllBytes();
            }

            job.updateProgress(50);

            // 3. Invoke internal AI inference
            long startTime = System.currentTimeMillis();
            AiClassificationResponse aiResponse = aiServiceClient.classify(
                    scan.getPublicId(),
                    scan.getPatient().getPatientCode(),
                    imageBytes
            );
            int durationMs = (int) (System.currentTimeMillis() - startTime);

            job.updateProgress(80);

            // 4. Resolve ModelVersion from model registry
            ModelVersion modelVersion = resolveModelVersion(aiResponse.modelName(),
                    aiResponse.modelVersion(), aiResponse.preprocessingVersion());

            String probabilitiesJson = objectMapper.writeValueAsString(aiResponse.probabilities());

            // 5. Persist prediction entity
            Prediction prediction = new Prediction(
                    scan,
                    job,
                    aiResponse.tumorType(),
                    aiResponse.confidence(),
                    probabilitiesJson,
                    modelVersion,
                    aiResponse.preprocessingVersion(),
                    Instant.now(),
                    aiResponse.isSynthetic()
            );
            prediction.setInferenceDurationMs(durationMs);
            Prediction savedPrediction = predictionRepository.save(prediction);

            // 6. Complete job and scan
            job.succeed(Instant.now());
            if (scan.getStatus().canTransitionTo(ScanStatus.COMPLETED)) {
                scan.transitionTo(ScanStatus.COMPLETED);
            }
            analysisJobRepository.save(job);
            scanRepository.save(scan);

            auditService.record(job.getRequestedBy(), job.getRequestedBy().getUsername(),
                    AuditAction.ANALYSIS_COMPLETED, RESOURCE_TYPE, scan.getPublicId(), true,
                    Map.of("predictedClass", aiResponse.tumorType(),
                            "confidence", aiResponse.confidence().toPlainString(),
                            "jobId", job.getPublicId()), null);

            return savedPrediction;

        } catch (Exception e) {
            log.warn("Analysis failed for scan {} job {}: {}", scan.getPublicId(), job.getPublicId(), e.getMessage());
            String errorCode = (e instanceof ApiException ae) ? ae.getErrorCode().name() : "AI_SERVICE_UNAVAILABLE";
            String reason = e.getMessage() != null ? e.getMessage() : "Inference service failure";

            job.fail(errorCode, reason, Instant.now());
            if (scan.getStatus().canTransitionTo(ScanStatus.FAILED)) {
                scan.fail(errorCode, reason);
            }
            analysisJobRepository.save(job);
            scanRepository.save(scan);

            auditService.record(job.getRequestedBy(), job.getRequestedBy().getUsername(),
                    AuditAction.ANALYSIS_FAILED, RESOURCE_TYPE, scan.getPublicId(), false,
                    Map.of("errorCode", errorCode, "jobId", job.getPublicId()), null);

            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE, "Inference pipeline failed", e);
        }
    }

    /**
     * Retrieves the latest tumor prediction for a scan.
     */
    @Transactional(readOnly = true)
    public PredictionResponse getPrediction(String scanPublicId,
                                           JwtService.AuthenticatedPrincipal principal) {
        User caller = requireCaller(principal);
        Scan scan = loadScanInScope(scanPublicId, caller);

        Prediction prediction = predictionRepository.findFirstByScanOrderByCreatedAtDesc(scan)
                .orElseThrow(() -> ResourceNotFoundException.prediction(scanPublicId));

        return predictionMapper.toResponse(prediction);
    }

    /**
     * Retrieves the status of a scan and its latest analysis job.
     */
    @Transactional(readOnly = true)
    public ScanStatusResponse getScanStatus(String scanPublicId,
                                           JwtService.AuthenticatedPrincipal principal) {
        User caller = requireCaller(principal);
        Scan scan = loadScanInScope(scanPublicId, caller);

        var latestJob = analysisJobRepository.findFirstByScanOrderByCreatedAtDesc(scan);

        return new ScanStatusResponse(
                scan.getPublicId(),
                scan.getStatus(),
                latestJob.map(AnalysisJob::getPublicId).orElse(null),
                latestJob.map(AnalysisJob::getStatus).orElse(null),
                latestJob.map(AnalysisJob::getProgressPercent).orElse((short) 0),
                scan.getFailureCode(),
                scan.getFailureReason()
        );
    }

    /**
     * Retrieves status of a specific analysis job.
     */
    @Transactional(readOnly = true)
    public AnalysisJobResponse getJob(String jobPublicId,
                                      JwtService.AuthenticatedPrincipal principal) {
        User caller = requireCaller(principal);
        AnalysisJob job = analysisJobRepository.findByPublicId(jobPublicId)
                .orElseThrow(() -> ResourceNotFoundException.job(jobPublicId));

        // Enforce scan scoping on the job
        loadScanInScope(job.getScan().getPublicId(), caller);

        return analysisJobMapper.toResponse(job);
    }

    private void prepareScanForQueue(Scan scan) {
        if (scan.getStatus() == ScanStatus.UPLOADED) {
            scan.transitionTo(ScanStatus.VALIDATING);
            scan.transitionTo(ScanStatus.VALIDATED);
            scan.transitionTo(ScanStatus.QUEUED);
        } else if (scan.getStatus() == ScanStatus.VALIDATING) {
            scan.transitionTo(ScanStatus.VALIDATED);
            scan.transitionTo(ScanStatus.QUEUED);
        } else if (scan.getStatus() == ScanStatus.VALIDATED) {
            scan.transitionTo(ScanStatus.QUEUED);
        } else if (scan.getStatus() == ScanStatus.FAILED) {
            scan.transitionTo(ScanStatus.QUEUED);
        }
        scanRepository.save(scan);
    }

    private ModelVersion resolveModelVersion(String modelName, String version, String preprocessingVersion) {
        return modelVersionRepository.findByModelNameAndModelVersion(modelName, version)
                .orElseGet(() -> modelVersionRepository.findByModelTypeAndStatus(ModelType.CLASSIFIER, ModelStatus.ACTIVE)
                        .stream().findFirst()
                        .orElseGet(() -> {
                            ModelVersion created = new ModelVersion(modelName, ModelType.CLASSIFIER,
                                    version, "PyTorch", preprocessingVersion);
                            created.activate();
                            return modelVersionRepository.save(created);
                        }));
    }

    private Scan loadScanInScope(String publicId, User caller) {
        Scan scan = scanRepository.findWithPatientByPublicId(publicId)
                .orElseThrow(() -> ResourceNotFoundException.scan(publicId));

        if (!hasUnrestrictedScope(caller) && !isCreatedBy(scan.getPatient(), caller)) {
            log.warn("User {} attempted out-of-scope access to scan {}", caller.getUsername(), publicId);
            throw ResourceNotFoundException.scan(publicId);
        }
        return scan;
    }

    private boolean hasUnrestrictedScope(User caller) {
        return caller.getRole() == Role.ADMIN || caller.getRole() == Role.RESEARCHER;
    }

    private boolean isCreatedBy(Patient patient, User caller) {
        User creator = patient.getCreatedBy();
        return creator != null && creator.getId() != null
                && creator.getId().equals(caller.getId());
    }

    private void requireWriteAccess(User caller) {
        if (caller.getRole() == Role.RESEARCHER) {
            throw new ApiException(ApiErrorCode.FORBIDDEN,
                    "Role RESEARCHER may not trigger analysis or modify scans");
        }
    }

    private User requireCaller(JwtService.AuthenticatedPrincipal principal) {
        if (principal == null) {
            throw new ApiException(ApiErrorCode.UNAUTHORIZED, "No authenticated principal");
        }
        return userRepository.findByPublicId(principal.publicId())
                .filter(User::isEnabled)
                .orElseThrow(() -> new ApiException(ApiErrorCode.UNAUTHORIZED,
                        "Authenticated principal no longer resolves to an enabled user"));
    }
}
