package com.braintwinx.service;

import com.braintwinx.audit.AuditService;
import com.braintwinx.dto.ExplanationResponse;
import com.braintwinx.dto.PageResponse;
import com.braintwinx.dto.ReportResponse;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.ExplanationStatus;
import com.braintwinx.entity.GrowthPrediction;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.Prediction;
import com.braintwinx.entity.Report;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.SegmentationResult;
import com.braintwinx.entity.User;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import com.braintwinx.exception.ResourceNotFoundException;
import com.braintwinx.report.PdfReportRenderer;
import com.braintwinx.report.ReportDataModel;
import com.braintwinx.repository.GrowthPredictionRepository;
import com.braintwinx.repository.PatientRepository;
import com.braintwinx.repository.PredictionRepository;
import com.braintwinx.repository.ReportRepository;
import com.braintwinx.repository.ScanRepository;
import com.braintwinx.repository.SegmentationResultRepository;
import com.braintwinx.repository.UserRepository;
import com.braintwinx.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service orchestrating clinical decision-support PDF report generation, storage, and retrieval (Phase 11).
 *
 * <p><strong>Key Medical-Safety & Security Invariants (Brief §15, §26, §54):</strong>
 * <ul>
 *   <li><strong>Caseload Scope & IDOR Protection:</strong> Clinicians can only issue and download reports
 *       for patients within their authorized caseload. Out-of-scope requests return 404.</li>
 *   <li><strong>Cryptographic Tamper-Evidence:</strong> Every issued PDF is hashed with SHA-256 upon creation.
 *       Upon download, the stored byte stream is re-verified against the immutable database digest.</li>
 *   <li><strong>"Not available" over Blankness:</strong> Unexecuted analytical stages explicitly print
 *       "Not available" rather than leaving blank areas that could be misread as negative findings.</li>
 *   <li><strong>Mandatory Medical Disclaimer:</strong> An unconditional disclaimer is printed on every report.</li>
 * </ul>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final String RESOURCE_TYPE = "REPORT";
    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM");

    private final ReportRepository reportRepository;
    private final ScanRepository scanRepository;
    private final PatientRepository patientRepository;
    private final PredictionRepository predictionRepository;
    private final SegmentationResultRepository segmentationResultRepository;
    private final GrowthPredictionRepository growthPredictionRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final ExplanationService explanationService;
    private final PdfReportRenderer pdfReportRenderer;
    private final AuditService auditService;

    public ReportService(
            ReportRepository reportRepository,
            ScanRepository scanRepository,
            PatientRepository patientRepository,
            PredictionRepository predictionRepository,
            SegmentationResultRepository segmentationResultRepository,
            GrowthPredictionRepository growthPredictionRepository,
            UserRepository userRepository,
            StorageService storageService,
            ExplanationService explanationService,
            PdfReportRenderer pdfReportRenderer,
            AuditService auditService
    ) {
        this.reportRepository = reportRepository;
        this.scanRepository = scanRepository;
        this.patientRepository = patientRepository;
        this.predictionRepository = predictionRepository;
        this.segmentationResultRepository = segmentationResultRepository;
        this.growthPredictionRepository = growthPredictionRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.explanationService = explanationService;
        this.pdfReportRenderer = pdfReportRenderer;
        this.auditService = auditService;
    }

    @Transactional
    public ReportResponse generateReport(
            String scanPublicId,
            JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        User caller = requireCaller(principal);
        requireWriteAccess(caller);

        Scan scan = scanRepository.findWithPatientByPublicId(scanPublicId)
                .orElseThrow(() -> ResourceNotFoundException.scan(scanPublicId));
        Patient patient = scan.getPatient();
        assertCaseloadAccess(caller, patient);

        // Retrieve latest analytical results
        Optional<Prediction> predOpt = predictionRepository.findFirstByScanOrderByCreatedAtDesc(scan);
        Optional<SegmentationResult> segOpt = segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(scan);
        Optional<GrowthPrediction> growthOpt = growthPredictionRepository.findFirstByPatientOrderByCreatedAtDesc(patient);

        // Generate verified explanation
        ExplanationResponse expResponse = explanationService.generateExplanation(scanPublicId, principal, httpRequest);

        // Assemble Data Model for PDF rendering
        String reportUuid = UUID.randomUUID().toString();
        Instant now = Instant.now();

        ReportDataModel dataModel = new ReportDataModel(
                reportUuid,
                now,
                caller.getUsername(),
                patient.getPatientCode(),
                patient.getBirthYear(),
                patient.getSex(),
                scan.getPublicId(),
                scan.getScanDate(),
                scan.getScanType(),
                scan.getImageWidth(),
                scan.getImageHeight(),
                "Automated neuro-oncology AI decision-support evaluation, volumetric segmentation, and growth trend analysis.",
                predOpt.map(Prediction::getPredictedClass).orElse(null),
                predOpt.map(Prediction::getConfidence).orElse(null),
                null, // class probabilities summary
                predOpt.map(p -> p.getModelVersion() != null ? p.getModelVersion().getModelName() : "BrainTumorCNN").orElse(null),
                predOpt.map(p -> p.getModelVersion() != null ? p.getModelVersion().getModelVersion() : "1.0.0").orElse(null),
                segOpt.map(SegmentationResult::isTumorDetected).orElse(null),
                segOpt.filter(SegmentationResult::isTumorDetected).map(SegmentationResult::getTumorAreaPx).orElse(null),
                segOpt.map(SegmentationResult::getMaskWidth).orElse(null),
                segOpt.map(SegmentationResult::getMaskHeight).orElse(null),
                segOpt.map(s -> s.getModelVersion() != null ? s.getModelVersion().getModelName() : "BrainTumorUNet").orElse(null),
                segOpt.map(s -> s.getModelVersion() != null ? s.getModelVersion().getModelVersion() : "1.0.0").orElse(null),
                growthOpt.map(g -> g.getStatus().name()).orElse(null),
                growthOpt.map(g -> g.getTrendDirection() != null ? g.getTrendDirection().name() : null).orElse(null),
                growthOpt.map(GrowthPrediction::getObservationCount).orElse(null),
                growthOpt.map(GrowthPrediction::getSpanDays).orElse(null),
                null, // growth velocity
                null, // doubling time
                growthOpt.map(g -> g.getModelVersion() != null ? g.getModelVersion().getModelName() : "TumorGrowthLSTM").orElse(null),
                growthOpt.map(g -> g.getModelVersion() != null ? g.getModelVersion().getModelVersion() : "1.0.0").orElse(null),
                expResponse.status(),
                expResponse.explanationText(),
                expResponse.provider(),
                expResponse.model(),
                expResponse.rejectionReason(),
                "1.0.0"
        );

        byte[] pdfBytes;
        try {
            pdfBytes = pdfReportRenderer.render(dataModel);
        } catch (IOException e) {
            log.error("Failed to render PDF report for scan {}: {}", scanPublicId, e.getMessage(), e);
            throw new ApiException(ApiErrorCode.REPORT_GENERATION_FAILED, "Failed to render PDF report: " + e.getMessage());
        }

        String contentSha256 = computeSha256(pdfBytes);
        String storageKey = "reports/" + LocalDate.now().format(DATE_PATH_FORMAT) + "/" + reportUuid + ".pdf";

        storageService.store(new ByteArrayInputStream(pdfBytes), storageKey);

        Report report = new Report(
                scan,
                patient,
                storageKey,
                contentSha256,
                pdfBytes.length,
                caller
        );
        report.linkResults(predOpt.orElse(null), segOpt.orElse(null), growthOpt.orElse(null));

        if (expResponse.status() == ExplanationStatus.INCLUDED) {
            report.includeExplanation(expResponse.explanationText(), expResponse.provider(), expResponse.model());
        } else if (expResponse.status() == ExplanationStatus.REJECTED) {
            report.markExplanationRejected(expResponse.provider(), expResponse.model());
        } else {
            report.markExplanationUnavailable();
        }

        report = reportRepository.save(report);

        auditService.record(
                caller,
                caller.getUsername(),
                AuditAction.REPORT_GENERATED,
                RESOURCE_TYPE,
                report.getPublicId(),
                true,
                Map.of("explanationStatus", report.getExplanationStatus().name()),
                httpRequest
        );

        return toResponse(report);
    }

    @Transactional(readOnly = true)
    public ReportResponse getReport(String reportPublicId, JwtService.AuthenticatedPrincipal principal) {
        User caller = requireCaller(principal);
        Report report = reportRepository.findWithContextByPublicId(reportPublicId)
                .orElseThrow(() -> ResourceNotFoundException.report(reportPublicId));
        assertCaseloadAccess(caller, report.getPatient());
        return toResponse(report);
    }

    @Transactional(readOnly = true)
    public DownloadResult downloadReport(
            String reportPublicId,
            JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        User caller = requireCaller(principal);
        Report report = reportRepository.findWithContextByPublicId(reportPublicId)
                .orElseThrow(() -> ResourceNotFoundException.report(reportPublicId));
        assertCaseloadAccess(caller, report.getPatient());

        byte[] pdfBytes;
        try (InputStream stream = storageService.load(report.getStorageKey())) {
            pdfBytes = stream.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to load report artifact from storage for key {}: {}", report.getStorageKey(), e.getMessage(), e);
            throw new ApiException(ApiErrorCode.STORAGE_FAILURE, "Report file could not be loaded from storage");
        }

        // Assert cryptographic content digest integrity
        String computedSha256 = computeSha256(pdfBytes);
        if (!computedSha256.equalsIgnoreCase(report.getContentSha256())) {
            log.error("Report content integrity violation detected for report {}: expected {} vs computed {}",
                    reportPublicId, report.getContentSha256(), computedSha256);
            throw new ApiException(ApiErrorCode.INTERNAL_ERROR, "Report content integrity check failed: file has been corrupted or altered");
        }

        auditService.record(
                caller,
                caller.getUsername(),
                AuditAction.REPORT_ACCESSED,
                RESOURCE_TYPE,
                report.getPublicId(),
                true,
                null,
                httpRequest
        );

        String filename = "BrainTwinX-Report-" + report.getPatient().getPatientCode() + "-" + report.getPublicId() + ".pdf";
        return new DownloadResult(new ByteArrayResource(pdfBytes), filename, pdfBytes.length);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportResponse> getPatientReports(
            String patientCode,
            Pageable pageable,
            JwtService.AuthenticatedPrincipal principal
    ) {
        User caller = requireCaller(principal);
        Patient patient = patientRepository.findByPatientCode(patientCode)
                .orElseThrow(() -> ResourceNotFoundException.patient(patientCode));
        assertCaseloadAccess(caller, patient);

        Page<ReportResponse> mapped = reportRepository.findByPatientOrderByCreatedAtDesc(patient, pageable)
                .map(this::toResponse);
        return PageResponse.from(mapped);
    }

    public record DownloadResult(Resource resource, String filename, long contentLength) {}

    private ReportResponse toResponse(Report r) {
        return new ReportResponse(
                r.getPublicId(),
                r.getScan().getPublicId(),
                r.getPatient().getPatientCode(),
                r.getExplanationStatus(),
                r.getExplanationProvider(),
                r.getExplanationModel(),
                r.getFileSizeBytes(),
                r.getContentSha256(),
                r.getCreatedAt(),
                r.getGeneratedBy() != null ? r.getGeneratedBy().getUsername() : "System"
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

    private void requireWriteAccess(User caller) {
        if (caller.getRole() == Role.RESEARCHER) {
            throw new ApiException(ApiErrorCode.FORBIDDEN,
                    "Role RESEARCHER may not trigger clinical report generation");
        }
    }

    private void assertCaseloadAccess(User caller, Patient patient) {
        if (!hasUnrestrictedScope(caller) && !isCreatedBy(patient, caller)) {
            log.warn("User {} attempted out-of-scope report access to patient {}", caller.getUsername(), patient.getPatientCode());
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

    private String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
