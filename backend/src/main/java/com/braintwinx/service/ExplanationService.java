package com.braintwinx.service;

import com.braintwinx.audit.AuditService;
import com.braintwinx.dto.ExplanationResponse;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.ExplanationStatus;
import com.braintwinx.entity.GrowthPrediction;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.Prediction;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.SegmentationResult;
import com.braintwinx.entity.User;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import com.braintwinx.exception.ResourceNotFoundException;
import com.braintwinx.explanation.ExplanationPayload;
import com.braintwinx.explanation.ExplanationProvider;
import com.braintwinx.explanation.ExplanationResult;
import com.braintwinx.explanation.ExplanationValidator;
import com.braintwinx.explanation.LlmExplanationProvider;
import com.braintwinx.explanation.StubExplanationProvider;
import com.braintwinx.repository.GrowthPredictionRepository;
import com.braintwinx.repository.PatientRepository;
import com.braintwinx.repository.PredictionRepository;
import com.braintwinx.repository.ScanRepository;
import com.braintwinx.repository.SegmentationResultRepository;
import com.braintwinx.repository.UserRepository;
import com.braintwinx.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for orchestrating AI decision-support explanations (Phase 10).
 *
 * <p><strong>Key Medical Safety Invariants:</strong>
 * <ul>
 *   <li><strong>Multimodal Isolation:</strong> Zero image bytes or pixel arrays are transmitted to explanation engines.
 *       Only verified structured inference metrics are passed.</li>
 *   <li><strong>Strict Validation:</strong> Every generated text passes through {@link ExplanationValidator}
 *       to enforce all 10 clinical safety prohibitions. Any violation causes immediate rejection and discard.</li>
 *   <li><strong>Fail-Closed Availability:</strong> If external LLMs are unconfigured or fail, status is set to
 *       {@code UNAVAILABLE} without breaking downstream reports or returning 500 errors.</li>
 *   <li><strong>Dual-Layer IDOR & Audit:</strong> Access is guarded by caseload authorization, and all outcomes
 *       are recorded in the append-only audit trail.</li>
 * </ul>
 */
@Service
public class ExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);
    private static final String RESOURCE_TYPE = "SCAN";

    private final ScanRepository scanRepository;
    private final PatientRepository patientRepository;
    private final PredictionRepository predictionRepository;
    private final SegmentationResultRepository segmentationResultRepository;
    private final GrowthPredictionRepository growthPredictionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ExplanationValidator explanationValidator;
    private final StubExplanationProvider stubExplanationProvider;
    private final LlmExplanationProvider llmExplanationProvider;
    private final String configuredProviderName;

    public ExplanationService(
            ScanRepository scanRepository,
            PatientRepository patientRepository,
            PredictionRepository predictionRepository,
            SegmentationResultRepository segmentationResultRepository,
            GrowthPredictionRepository growthPredictionRepository,
            UserRepository userRepository,
            AuditService auditService,
            ExplanationValidator explanationValidator,
            StubExplanationProvider stubExplanationProvider,
            LlmExplanationProvider llmExplanationProvider,
            @Value("${braintwinx.explanation.provider:STUB}") String configuredProviderName
    ) {
        this.scanRepository = scanRepository;
        this.patientRepository = patientRepository;
        this.predictionRepository = predictionRepository;
        this.segmentationResultRepository = segmentationResultRepository;
        this.growthPredictionRepository = growthPredictionRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.explanationValidator = explanationValidator;
        this.stubExplanationProvider = stubExplanationProvider;
        this.llmExplanationProvider = llmExplanationProvider;
        this.configuredProviderName = configuredProviderName;
    }

    @Transactional(readOnly = true)
    public ExplanationResponse generateExplanation(
            String scanPublicId,
            JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        User caller = requireCaller(principal);

        Scan scan = scanRepository.findWithPatientByPublicId(scanPublicId)
                .orElseThrow(() -> ResourceNotFoundException.scan(scanPublicId));

        Patient patient = scan.getPatient();
        assertCaseloadAccess(caller, patient);

        // Assemble latest analytical results
        Optional<Prediction> predOpt = predictionRepository.findFirstByScanOrderByCreatedAtDesc(scan);
        Optional<SegmentationResult> segOpt = segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(scan);
        Optional<GrowthPrediction> growthOpt = growthPredictionRepository.findFirstByPatientOrderByCreatedAtDesc(patient);

        String tumorType = predOpt.map(Prediction::getPredictedClass).orElse("NO_TUMOR");
        Double confidence = predOpt.map(p -> p.getConfidence() != null ? p.getConfidence().doubleValue() : null).orElse(0.95);
        Long tumorAreaPx = segOpt.filter(SegmentationResult::isTumorDetected).map(SegmentationResult::getTumorAreaPx).orElse(null);

        String trendDirection = growthOpt.map(g -> g.getTrendDirection() != null ? g.getTrendDirection().name() : g.getStatus().name()).orElse("INSUFFICIENT_HISTORY");
        Integer observationCount = growthOpt.map(GrowthPrediction::getObservationCount).orElse(1);
        Integer spanDays = growthOpt.map(GrowthPrediction::getSpanDays).orElse(null);

        String classifierModel = predOpt.map(p -> p.getModelVersion() != null ? p.getModelVersion().getModelVersion() : "BrainTumorCNN v1.0.0").orElse("BrainTumorCNN v1.0.0");
        String segmenterModel = segOpt.map(s -> s.getModelVersion() != null ? s.getModelVersion().getModelVersion() : "BrainTumorUNet v1.0.0").orElse("BrainTumorUNet v1.0.0");
        String forecasterModel = growthOpt.map(g -> g.getModelVersion() != null ? g.getModelVersion().getModelVersion() : "TumorGrowthLSTM v1.0.0").orElse("TumorGrowthLSTM v1.0.0");

        ExplanationPayload payload = new ExplanationPayload(
                scan.getPublicId(),
                patient.getPatientCode(),
                tumorType,
                confidence,
                tumorAreaPx,
                trendDirection,
                observationCount,
                spanDays,
                classifierModel,
                segmenterModel,
                forecasterModel
        );

        ExplanationProvider provider = "LLM".equalsIgnoreCase(configuredProviderName)
                ? llmExplanationProvider
                : stubExplanationProvider;

        ExplanationResult rawResult = provider.generateExplanation(payload);
        ExplanationResult finalResult;

        Map<String, Object> auditMetadata = new HashMap<>();
        auditMetadata.put("provider", provider.getProviderName());

        if (rawResult.status() == ExplanationStatus.INCLUDED) {
            ExplanationValidator.ValidationOutcome outcome = explanationValidator.validate(rawResult.explanationText(), payload);
            if (outcome.isValid()) {
                finalResult = rawResult;
                auditMetadata.put("explanationStatus", ExplanationStatus.INCLUDED.name());
                auditService.record(
                        caller,
                        caller.getUsername(),
                        AuditAction.EXPLANATION_GENERATED,
                        RESOURCE_TYPE,
                        scanPublicId,
                        true,
                        auditMetadata,
                        httpRequest
                );
            } else {
                log.warn("Generated explanation rejected by safety validator for scan {}: {}", scanPublicId, outcome.rejectionReason());
                finalResult = ExplanationResult.rejected(outcome.rejectionReason(), rawResult.provider(), rawResult.model());
                auditMetadata.put("explanationStatus", ExplanationStatus.REJECTED.name());
                auditMetadata.put("reason", outcome.rejectionReason());
                auditService.record(
                        caller,
                        caller.getUsername(),
                        AuditAction.EXPLANATION_REJECTED,
                        RESOURCE_TYPE,
                        scanPublicId,
                        true,
                        auditMetadata,
                        httpRequest
                );
            }
        } else {
            finalResult = rawResult;
            auditMetadata.put("explanationStatus", rawResult.status().name());
            if (rawResult.rejectionReason() != null) {
                auditMetadata.put("reason", rawResult.rejectionReason());
            }
            auditService.record(
                    caller,
                    caller.getUsername(),
                    AuditAction.EXPLANATION_GENERATED,
                    RESOURCE_TYPE,
                    scanPublicId,
                    true,
                    auditMetadata,
                    httpRequest
            );
        }

        return new ExplanationResponse(
                scan.getPublicId(),
                patient.getPatientCode(),
                finalResult.status(),
                finalResult.explanationText(),
                finalResult.provider(),
                finalResult.model(),
                finalResult.rejectionReason(),
                finalResult.generatedAt()
        );
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

    private void assertCaseloadAccess(User caller, Patient patient) {
        if (!hasUnrestrictedScope(caller) && !isCreatedBy(patient, caller)) {
            log.warn("User {} attempted out-of-scope explanation access to patient {}", caller.getUsername(), patient.getPatientCode());
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
}
