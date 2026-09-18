package com.braintwinx.service;

import com.braintwinx.audit.AuditService;
import com.braintwinx.client.AiServiceClient;
import com.braintwinx.client.AiSegmentationResponse;
import com.braintwinx.dto.SegmentationResponse;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.ModelStatus;
import com.braintwinx.entity.ModelType;
import com.braintwinx.entity.ModelVersion;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.ScanStatus;
import com.braintwinx.entity.SegmentationResult;
import com.braintwinx.entity.User;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import com.braintwinx.exception.ResourceNotFoundException;
import com.braintwinx.mapper.SegmentationMapper;
import com.braintwinx.repository.ModelVersionRepository;
import com.braintwinx.repository.PredictionRepository;
import com.braintwinx.repository.ScanRepository;
import com.braintwinx.repository.SegmentationResultRepository;
import com.braintwinx.repository.UserRepository;
import com.braintwinx.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SegmentationService {

    private static final Logger log = LoggerFactory.getLogger(SegmentationService.class);
    private static final String RESOURCE_TYPE = "Scan";

    private final ScanRepository scanRepository;
    private final SegmentationResultRepository segmentationResultRepository;
    private final PredictionRepository predictionRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final AiServiceClient aiServiceClient;
    private final AuditService auditService;
    private final SegmentationMapper segmentationMapper;

    public SegmentationService(
            ScanRepository scanRepository,
            SegmentationResultRepository segmentationResultRepository,
            PredictionRepository predictionRepository,
            ModelVersionRepository modelVersionRepository,
            UserRepository userRepository,
            StorageService storageService,
            AiServiceClient aiServiceClient,
            AuditService auditService,
            SegmentationMapper segmentationMapper
    ) {
        this.scanRepository = scanRepository;
        this.segmentationResultRepository = segmentationResultRepository;
        this.predictionRepository = predictionRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.aiServiceClient = aiServiceClient;
        this.auditService = auditService;
        this.segmentationMapper = segmentationMapper;
    }

    /**
     * Executes synchronous U-Net segmentation on an MRI scan.
     * Persists the generated mask as an independent file artefact and saves SegmentationResult in MySQL.
     * Original scan bytes are immutable and never modified.
     */
    @Transactional
    public SegmentationResponse segmentScanDirect(
            String scanPublicId,
            JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        User caller = requireCaller(principal);
        requireWriteAccess(caller);

        Scan scan = loadScanInScope(scanPublicId, caller);

        if (!scan.getPatient().isActive()) {
            throw new ApiException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot segment scan for archived patient: " + scan.getPatient().getPatientCode());
        }

        // Fail-closed safety check
        aiServiceClient.ensureReady();

        // Advance scan state machine if applicable
        advanceScanToProcessing(scan);

        byte[] scanBytes;
        try (InputStream is = storageService.load(scan.getStorageKey())) {
            scanBytes = is.readAllBytes();
        } catch (Exception e) {
            log.error("Failed to load scan bytes from storage key {}: {}", scan.getStorageKey(), e.getMessage());
            scan.fail("STORAGE_READ_ERROR", "Failed to load scan image from storage");
            scanRepository.save(scan);
            throw new ApiException(ApiErrorCode.INTERNAL_ERROR, "Could not read scan image from storage", e);
        }

        AiSegmentationResponse aiResponse;
        try {
            aiResponse = aiServiceClient.segment(scan.getPublicId(), scan.getPatient().getPatientCode(), scanBytes);
        } catch (ApiException e) {
            log.warn("Segmentation inference failed for scan {}: {}", scanPublicId, e.getMessage());
            scan.fail(e.getErrorCode().name(), e.getMessage());
            scanRepository.save(scan);
            auditService.record(caller, caller.getUsername(), AuditAction.ANALYSIS_FAILED,
                    RESOURCE_TYPE, scanPublicId, false, Map.of("error", e.getMessage()), httpRequest);
            throw e;
        }

        String maskStorageKey = null;
        if (aiResponse.tumorDetected() && aiResponse.maskBase64() != null && !aiResponse.maskBase64().isBlank()) {
            byte[] maskBytes = Base64.getDecoder().decode(aiResponse.maskBase64());
            maskStorageKey = "masks/" + UUID.randomUUID() + ".png";
            storageService.store(new ByteArrayInputStream(maskBytes), maskStorageKey);
        }

        ModelVersion modelVersion = resolveModelVersion(
                aiResponse.modelName(),
                aiResponse.modelVersion(),
                aiResponse.preprocessingVersion()
        );

        SegmentationResult result;
        if (aiResponse.tumorDetected()) {
            long areaPx = aiResponse.tumorAreaPx() != null ? aiResponse.tumorAreaPx() : 0L;
            int width = aiResponse.maskWidth() != null ? aiResponse.maskWidth() : 224;
            int height = aiResponse.maskHeight() != null ? aiResponse.maskHeight() : 224;

            result = SegmentationResult.detected(
                    scan,
                    null,
                    maskStorageKey,
                    areaPx,
                    width,
                    height,
                    modelVersion,
                    aiResponse.preprocessingVersion(),
                    Instant.now(),
                    aiResponse.isSynthetic()
            );

            if (aiResponse.bboxX() != null && aiResponse.bboxY() != null &&
                    aiResponse.bboxWidth() != null && aiResponse.bboxHeight() != null) {
                result.withBoundingBox(
                        aiResponse.bboxX(),
                        aiResponse.bboxY(),
                        aiResponse.bboxWidth(),
                        aiResponse.bboxHeight()
                );
            }
        } else {
            result = SegmentationResult.notDetected(
                    scan,
                    null,
                    modelVersion,
                    aiResponse.preprocessingVersion(),
                    Instant.now(),
                    aiResponse.isSynthetic()
            );
        }

        // Link latest prediction if present
        predictionRepository.findFirstByScanOrderByCreatedAtDesc(scan)
                .ifPresent(result::linkPrediction);

        SegmentationResult saved = segmentationResultRepository.save(result);

        if (scan.getStatus() == ScanStatus.PROCESSING) {
            scan.transitionTo(ScanStatus.COMPLETED);
            scanRepository.save(scan);
        }

        auditService.record(caller, caller.getUsername(), AuditAction.ANALYSIS_COMPLETED,
                RESOURCE_TYPE, scanPublicId, true,
                Map.of("tumorDetected", result.isTumorDetected(), "areaPx", String.valueOf(result.getTumorAreaPx())),
                httpRequest);

        return segmentationMapper.toResponse(saved);
    }

    /**
     * Retrieves the latest segmentation result for a scan.
     */
    @Transactional(readOnly = true)
    public SegmentationResponse getSegmentation(
            String scanPublicId,
            JwtService.AuthenticatedPrincipal principal
    ) {
        User caller = requireCaller(principal);
        Scan scan = loadScanInScope(scanPublicId, caller);

        SegmentationResult result = segmentationResultRepository
                .findFirstByScanOrderByCreatedAtDesc(scan)
                .orElseThrow(() -> ResourceNotFoundException.segmentation(scanPublicId));

        return segmentationMapper.toResponse(result);
    }

    /**
     * Loads the raw PNG mask artefact stream for display or download.
     */
    @Transactional(readOnly = true)
    public InputStream loadMask(
            String scanPublicId,
            JwtService.AuthenticatedPrincipal principal
    ) {
        User caller = requireCaller(principal);
        Scan scan = loadScanInScope(scanPublicId, caller);

        SegmentationResult result = segmentationResultRepository
                .findFirstByScanOrderByCreatedAtDesc(scan)
                .orElseThrow(() -> ResourceNotFoundException.segmentation(scanPublicId));

        if (!result.isTumorDetected() || result.getMaskStorageKey() == null) {
            throw ResourceNotFoundException.segmentation(scanPublicId);
        }

        return storageService.load(result.getMaskStorageKey());
    }

    private ModelVersion resolveModelVersion(String modelName, String version, String preprocessingVersion) {
        return modelVersionRepository.findByModelNameAndModelVersion(modelName, version)
                .orElseGet(() -> modelVersionRepository.findByModelTypeAndStatus(ModelType.SEGMENTER, ModelStatus.ACTIVE)
                        .stream().findFirst()
                        .orElseGet(() -> {
                            ModelVersion created = new ModelVersion(modelName, ModelType.SEGMENTER,
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
                    "Role RESEARCHER may not trigger segmentation or modify scans");
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

    private void advanceScanToProcessing(Scan scan) {
        if (scan.getStatus() == ScanStatus.UPLOADED) {
            scan.transitionTo(ScanStatus.VALIDATING);
            scan.transitionTo(ScanStatus.VALIDATED);
            scan.transitionTo(ScanStatus.QUEUED);
            scan.transitionTo(ScanStatus.PROCESSING);
            scanRepository.save(scan);
        } else if (scan.getStatus() == ScanStatus.VALIDATED) {
            scan.transitionTo(ScanStatus.QUEUED);
            scan.transitionTo(ScanStatus.PROCESSING);
            scanRepository.save(scan);
        } else if (scan.getStatus() == ScanStatus.QUEUED) {
            scan.transitionTo(ScanStatus.PROCESSING);
            scanRepository.save(scan);
        }
    }
}
