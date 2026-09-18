package com.braintwinx.service;

import com.braintwinx.audit.AuditService;
import com.braintwinx.client.AiLongitudinalForecastRequest;
import com.braintwinx.client.AiLongitudinalForecastResponse;
import com.braintwinx.client.AiServiceClient;
import com.braintwinx.dto.GrowthPredictionResponse;
import com.braintwinx.dto.PageResponse;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.GrowthPrediction;
import com.braintwinx.entity.ModelStatus;
import com.braintwinx.entity.ModelType;
import com.braintwinx.entity.ModelVersion;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.SegmentationResult;
import com.braintwinx.entity.TrendDirection;
import com.braintwinx.entity.User;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import com.braintwinx.exception.ResourceNotFoundException;
import com.braintwinx.mapper.GrowthPredictionMapper;
import com.braintwinx.repository.GrowthPredictionRepository;
import com.braintwinx.repository.ModelVersionRepository;
import com.braintwinx.repository.PatientRepository;
import com.braintwinx.repository.ScanRepository;
import com.braintwinx.repository.SegmentationResultRepository;
import com.braintwinx.repository.UserRepository;
import com.braintwinx.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Service for longitudinal tumor growth analysis and LSTM forecasting (Phase 9).
 *
 * <p><strong>Medical safety invariants enforced (brief section 13, section 44, section 46):</strong>
 * <ul>
 *   <li>The central invariant: {@code INSUFFICIENT_HISTORY} is a first-class, auditable outcome.
 *       When observations are below threshold (minimum 3 scans, spanning at least 30 days per A-5),
 *       no forecast or trend is fabricated. Zero AI service call is made.</li>
 *   <li>Output is strictly labeled as a <strong>MODEL-BASED TREND ESTIMATE</strong>. Language
 *       never implies certainty about future growth.</li>
 *   <li>Fails closed with {@code 503 AI_SERVICE_UNAVAILABLE} if AI weights are missing.</li>
 *   <li>Caseload scope enforcement protects patient records against IDOR.</li>
 * </ul>
 */
@Service
public class GrowthPredictionService {

    private static final Logger log = LoggerFactory.getLogger(GrowthPredictionService.class);
    private static final String RESOURCE_TYPE = "PATIENT";

    public static final int DEFAULT_MIN_OBSERVATIONS = 3;
    public static final int DEFAULT_MIN_SPAN_DAYS = 30;

    private final GrowthPredictionRepository growthPredictionRepository;
    private final PatientRepository patientRepository;
    private final ScanRepository scanRepository;
    private final SegmentationResultRepository segmentationResultRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final UserRepository userRepository;
    private final AiServiceClient aiServiceClient;
    private final AuditService auditService;
    private final GrowthPredictionMapper growthPredictionMapper;
    private final ObjectMapper objectMapper;

    public GrowthPredictionService(
            GrowthPredictionRepository growthPredictionRepository,
            PatientRepository patientRepository,
            ScanRepository scanRepository,
            SegmentationResultRepository segmentationResultRepository,
            ModelVersionRepository modelVersionRepository,
            UserRepository userRepository,
            AiServiceClient aiServiceClient,
            AuditService auditService,
            GrowthPredictionMapper growthPredictionMapper,
            ObjectMapper objectMapper
    ) {
        this.growthPredictionRepository = growthPredictionRepository;
        this.patientRepository = patientRepository;
        this.scanRepository = scanRepository;
        this.segmentationResultRepository = segmentationResultRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.userRepository = userRepository;
        this.aiServiceClient = aiServiceClient;
        this.auditService = auditService;
        this.growthPredictionMapper = growthPredictionMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes longitudinal growth analysis for a patient.
     *
     * @param patientCode target patient code
     * @param principal authenticated user
     * @param httpRequest current HTTP request for audit logging
     * @return {@link GrowthPredictionResponse} result
     */
    @Transactional
    public GrowthPredictionResponse analyzeGrowth(
            String patientCode,
            JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        User caller = requireCaller(principal);
        requireWriteAccess(caller);

        Patient patient = patientRepository.findByPatientCode(patientCode)
                .orElseThrow(() -> ResourceNotFoundException.patient(patientCode));

        assertCaseloadAccess(caller, patient);

        // Assemble chronological scan history with segmentation results
        List<Scan> chronologicalScans = scanRepository.findByPatientOrderByScanDateAsc(patient);
        List<ValidObservation> observations = new ArrayList<>();

        for (Scan scan : chronologicalScans) {
            Optional<SegmentationResult> segOpt = segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(scan);
            if (segOpt.isPresent() && segOpt.get().isTumorDetected() && segOpt.get().getTumorAreaPx() != null) {
                observations.add(new ValidObservation(scan, segOpt.get()));
            }
        }

        int count = observations.size();
        Integer spanDays = null;
        if (count >= 2) {
            LocalDate first = observations.get(0).scan().getScanDate();
            LocalDate last = observations.get(count - 1).scan().getScanDate();
            spanDays = (int) ChronoUnit.DAYS.between(first, last);
        }

        Scan triggeringScan = observations.isEmpty() ? null : observations.get(count - 1).scan();

        // Evaluate sufficiency policy (ASSUMPTIONS.md A-5)
        boolean isSufficient = count >= DEFAULT_MIN_OBSERVATIONS && spanDays != null && spanDays >= DEFAULT_MIN_SPAN_DAYS;

        if (!isSufficient) {
            log.info("Patient {} has insufficient longitudinal history: {} observations, {} days span. Recording INSUFFICIENT_HISTORY.",
                    patientCode, count, spanDays);

            GrowthPrediction insufficient = GrowthPrediction.insufficientHistory(
                    patient, triggeringScan, null, count, spanDays
            );
            insufficient = growthPredictionRepository.save(insufficient);

            auditService.record(
                    caller,
                    caller.getUsername(),
                    AuditAction.ANALYSIS_COMPLETED,
                    RESOURCE_TYPE,
                    patientCode,
                    true,
                    Map.of("reason", "INSUFFICIENT_HISTORY", "observationCount", count),
                    httpRequest
            );

            return growthPredictionMapper.toResponse(insufficient);
        }

        // History is sufficient: invoke AI LSTM forecaster
        aiServiceClient.ensureReady();

        LocalDate baseDate = observations.get(0).scan().getScanDate();
        List<AiLongitudinalForecastRequest.ObservationPointDto> obsDtos = new ArrayList<>();
        for (ValidObservation obs : observations) {
            int days = (int) ChronoUnit.DAYS.between(baseDate, obs.scan().getScanDate());
            obsDtos.add(new AiLongitudinalForecastRequest.ObservationPointDto(
                    obs.scan().getPublicId(),
                    obs.scan().getScanDate().toString(),
                    days,
                    obs.segmentation().getTumorAreaPx()
            ));
        }

        AiLongitudinalForecastRequest forecastRequest = new AiLongitudinalForecastRequest(
                patient.getPatientCode(),
                obsDtos,
                List.of(30, 60, 90)
        );

        AiLongitudinalForecastResponse aiResp;
        try {
            aiResp = aiServiceClient.forecastGrowth(forecastRequest);
        } catch (ApiException e) {
            log.warn("AI service failure during longitudinal forecasting for patient {}: {}", patientCode, e.getMessage());
            GrowthPrediction failed = GrowthPrediction.failed(patient, triggeringScan, null, count, spanDays);
            growthPredictionRepository.save(failed);
            throw e;
        }

        ModelVersion modelVersion = modelVersionRepository.findByModelNameAndModelVersion(
                aiResp.modelName(), aiResp.modelVersion()
        ).orElseGet(() -> modelVersionRepository.findByModelTypeAndStatus(ModelType.FORECASTER, ModelStatus.ACTIVE)
                .stream().findFirst()
                .orElse(null));

        TrendDirection trend;
        try {
            trend = TrendDirection.valueOf(aiResp.trendDirection());
        } catch (Exception e) {
            trend = TrendDirection.INDETERMINATE;
        }

        String forecastJson;
        try {
            forecastJson = objectMapper.writeValueAsString(aiResp.forecast());
        } catch (Exception e) {
            log.error("Failed to serialize forecast JSON", e);
            forecastJson = "[]";
        }

        GrowthPrediction completed = GrowthPrediction.completed(
                patient,
                triggeringScan,
                null,
                count,
                spanDays,
                trend,
                forecastJson,
                modelVersion,
                aiResp.preprocessingVersion(),
                Instant.now(),
                aiResp.isSynthetic()
        );

        completed = growthPredictionRepository.save(completed);

        auditService.record(
                caller,
                caller.getUsername(),
                AuditAction.ANALYSIS_COMPLETED,
                RESOURCE_TYPE,
                patientCode,
                true,
                Map.of("modelName", modelVersion != null ? modelVersion.getModelName() : "BrainTumorLSTM",
                        "modelVersion", modelVersion != null ? modelVersion.getModelVersion() : "1.0.0",
                        "observationCount", count,
                        "isSynthetic", aiResp.isSynthetic()),
                httpRequest
        );

        return growthPredictionMapper.toResponse(completed);
    }

    /**
     * Retrieves the latest growth prediction for a patient.
     */
    @Transactional(readOnly = true)
    public GrowthPredictionResponse getLatestGrowthPrediction(
            String patientCode,
            JwtService.AuthenticatedPrincipal principal
    ) {
        User caller = requireCaller(principal);
        Patient patient = patientRepository.findByPatientCode(patientCode)
                .orElseThrow(() -> ResourceNotFoundException.patient(patientCode));

        assertCaseloadAccess(caller, patient);

        GrowthPrediction prediction = growthPredictionRepository.findFirstByPatientOrderByCreatedAtDesc(patient)
                .orElseThrow(() -> ResourceNotFoundException.growthPrediction(patientCode));

        return growthPredictionMapper.toResponse(prediction);
    }

    /**
     * Retrieves paginated historical growth predictions for a patient.
     */
    @Transactional(readOnly = true)
    public PageResponse<GrowthPredictionResponse> getGrowthPredictionHistory(
            String patientCode,
            Pageable pageable,
            JwtService.AuthenticatedPrincipal principal
    ) {
        User caller = requireCaller(principal);
        Patient patient = patientRepository.findByPatientCode(patientCode)
                .orElseThrow(() -> ResourceNotFoundException.patient(patientCode));

        assertCaseloadAccess(caller, patient);

        Page<GrowthPrediction> page = growthPredictionRepository.findByPatientOrderByCreatedAtDesc(patient, pageable);
        return PageResponse.from(page.map(growthPredictionMapper::toResponse));
    }

    private User requireCaller(JwtService.AuthenticatedPrincipal principal) {
        if (principal == null) {
            throw new ApiException(ApiErrorCode.UNAUTHORIZED, "No authenticated principal");
        }
        return userRepository.findByPublicId(principal.publicId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.UNAUTHORIZED, "Caller user not found"));
    }

    private void requireWriteAccess(User caller) {
        if (caller.getRole() == Role.RESEARCHER) {
            throw new ApiException(ApiErrorCode.FORBIDDEN,
                    "Role RESEARCHER may not trigger longitudinal growth analysis");
        }
    }

    private void assertCaseloadAccess(User caller, Patient patient) {
        if (!hasUnrestrictedScope(caller) && !isCreatedBy(patient, caller)) {
            log.warn("User {} attempted out-of-scope access to patient {}", caller.getUsername(), patient.getPatientCode());
            throw ResourceNotFoundException.patient(patient.getPatientCode());
        }
    }

    private boolean hasUnrestrictedScope(User caller) {
        return caller.getRole() == Role.ADMIN || caller.getRole() == Role.RESEARCHER;
    }

    private boolean isCreatedBy(Patient patient, User caller) {
        User creator = patient.getCreatedBy();
        return creator != null && creator.getId() != null
                && creator.getId().equals(caller.getId());
    }

    private record ValidObservation(Scan scan, SegmentationResult segmentation) {}
}
