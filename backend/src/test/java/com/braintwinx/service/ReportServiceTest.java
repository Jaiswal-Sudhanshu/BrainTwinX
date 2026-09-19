package com.braintwinx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.braintwinx.audit.AuditService;
import com.braintwinx.dto.ExplanationResponse;
import com.braintwinx.dto.ReportResponse;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.ExplanationStatus;
import com.braintwinx.entity.ModelType;
import com.braintwinx.entity.ModelVersion;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.PatientSex;
import com.braintwinx.entity.Prediction;
import com.braintwinx.entity.Report;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.ScanType;
import com.braintwinx.entity.SegmentationResult;
import com.braintwinx.entity.User;
import com.braintwinx.exception.ApiException;
import com.braintwinx.exception.ResourceNotFoundException;
import com.braintwinx.report.PdfReportRenderer;
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
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService")
class ReportServiceTest {

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ScanRepository scanRepository;

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private PredictionRepository predictionRepository;

    @Mock
    private SegmentationResultRepository segmentationResultRepository;

    @Mock
    private GrowthPredictionRepository growthPredictionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private ExplanationService explanationService;

    @Mock
    private AuditService auditService;

    @Mock
    private HttpServletRequest httpRequest;

    private PdfReportRenderer pdfReportRenderer;
    private ReportService reportService;

    private User doctorA;
    private User doctorB;
    private Patient patientA;
    private Scan scanA;
    private JwtService.AuthenticatedPrincipal principalA;
    private JwtService.AuthenticatedPrincipal principalB;

    @BeforeEach
    void setUp() throws Exception {
        pdfReportRenderer = new PdfReportRenderer();

        reportService = new ReportService(
                reportRepository,
                scanRepository,
                patientRepository,
                predictionRepository,
                segmentationResultRepository,
                growthPredictionRepository,
                userRepository,
                storageService,
                explanationService,
                pdfReportRenderer,
                auditService
        );

        doctorA = new User("dr_curie", "curie@hospital.org", "hash", "Dr Curie", Role.DOCTOR);
        setField(doctorA, "id", 1L);

        doctorB = new User("dr_fleming", "fleming@hospital.org", "hash", "Dr Fleming", Role.DOCTOR);
        setField(doctorB, "id", 2L);

        principalA = new JwtService.AuthenticatedPrincipal(doctorA.getPublicId(), doctorA.getRole());
        principalB = new JwtService.AuthenticatedPrincipal(doctorB.getPublicId(), doctorB.getRole());

        patientA = new Patient("PT-5050", (short) 1982, PatientSex.FEMALE, doctorA);
        setField(patientA, "id", 10L);

        scanA = new Scan(patientA, LocalDate.now(), ScanType.MRI_T1C, "scans/scan-5050.png", "image/png", 2048L, "sha256", doctorA);
        setField(scanA, "id", 20L);
    }

    @Test
    @DisplayName("Successfully generates, stores, and audits clinical decision-support PDF report")
    void generateReportSuccess() {
        when(userRepository.findByPublicId(principalA.publicId())).thenReturn(Optional.of(doctorA));
        when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

        ModelVersion classifierModel = new ModelVersion("BrainTumorCNN", ModelType.CLASSIFIER, "1.0.0", "PyTorch", "1.0.0");
        Prediction prediction = new Prediction(
                scanA, null, "GLIOMA", new BigDecimal("0.98"), "{\"GLIOMA\": 0.98}",
                classifierModel, "1.0.0", Instant.now(), false
        );
        when(predictionRepository.findFirstByScanOrderByCreatedAtDesc(scanA)).thenReturn(Optional.of(prediction));

        ModelVersion segmenterModel = new ModelVersion("BrainTumorUNet", ModelType.SEGMENTER, "1.0.0", "PyTorch", "1.0.0");
        SegmentationResult seg = SegmentationResult.detected(
                scanA, null, "masks/mask.png", 1400L, 224, 224,
                segmenterModel, "1.0.0", Instant.now(), false
        );
        when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(scanA)).thenReturn(Optional.of(seg));
        when(growthPredictionRepository.findFirstByPatientOrderByCreatedAtDesc(patientA)).thenReturn(Optional.empty());

        ExplanationResponse exp = new ExplanationResponse(
                scanA.getPublicId(), patientA.getPatientCode(), ExplanationStatus.INCLUDED,
                "Model findings consistent with GLIOMA. Radiologist review required.",
                "STUB", "Deterministic-v1", null, Instant.now()
        );
        when(explanationService.generateExplanation(eq(scanA.getPublicId()), eq(principalA), any())).thenReturn(exp);

        when(storageService.store(any(), anyString())).thenReturn("reports/test.pdf");
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));

        ReportResponse response = reportService.generateReport(scanA.getPublicId(), principalA, httpRequest);

        assertThat(response).isNotNull();
        assertThat(response.scanPublicId()).isEqualTo(scanA.getPublicId());
        assertThat(response.patientCode()).isEqualTo(patientA.getPatientCode());
        assertThat(response.explanationStatus()).isEqualTo(ExplanationStatus.INCLUDED);
        assertThat(response.contentSha256()).isNotNull();
        assertThat(response.fileSizeBytes()).isGreaterThan(0);

        verify(storageService).store(any(), anyString());
        verify(reportRepository).save(any(Report.class));
        verify(auditService).record(
                eq(doctorA),
                eq(doctorA.getUsername()),
                eq(AuditAction.REPORT_GENERATED),
                eq("REPORT"),
                anyString(),
                eq(true),
                any(),
                eq(httpRequest)
        );
    }

    @Test
    @DisplayName("Rejects out-of-scope caseload report generation with 404 (IDOR defense)")
    void rejectsOutOfScopeGeneration() {
        when(userRepository.findByPublicId(principalB.publicId())).thenReturn(Optional.of(doctorB));
        when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

        assertThatThrownBy(() -> reportService.generateReport(scanA.getPublicId(), principalB, httpRequest))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Successfully downloads report and verifies SHA-256 cryptographic digest")
    void downloadReportVerifiesDigest() throws Exception {
        byte[] fakePdfBytes = "%PDF-1.4 Mock valid content".getBytes();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fakePdfBytes));

        Report report = new Report(scanA, patientA, "reports/fake.pdf", sha256, fakePdfBytes.length, doctorA);

        when(userRepository.findByPublicId(principalA.publicId())).thenReturn(Optional.of(doctorA));
        when(reportRepository.findWithContextByPublicId(report.getPublicId())).thenReturn(Optional.of(report));
        when(storageService.load(report.getStorageKey())).thenReturn(new ByteArrayInputStream(fakePdfBytes));

        ReportService.DownloadResult download = reportService.downloadReport(report.getPublicId(), principalA, httpRequest);

        assertThat(download).isNotNull();
        assertThat(download.contentLength()).isEqualTo(fakePdfBytes.length);
        assertThat(download.filename()).contains(patientA.getPatientCode());

        verify(auditService).record(
                eq(doctorA),
                eq(doctorA.getUsername()),
                eq(AuditAction.REPORT_ACCESSED),
                eq("REPORT"),
                eq(report.getPublicId()),
                eq(true),
                eq(null),
                eq(httpRequest)
        );
    }

    @Test
    @DisplayName("Download throws integrity violation error when stored file hash does not match database record")
    void downloadRejectsTamperedFile() throws Exception {
        byte[] originalBytes = "%PDF-1.4 Original content".getBytes();
        String originalSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(originalBytes));

        Report report = new Report(scanA, patientA, "reports/tampered.pdf", originalSha256, originalBytes.length, doctorA);

        byte[] tamperedBytes = "%PDF-1.4 Tampered attacker content".getBytes();

        when(userRepository.findByPublicId(principalA.publicId())).thenReturn(Optional.of(doctorA));
        when(reportRepository.findWithContextByPublicId(report.getPublicId())).thenReturn(Optional.of(report));
        when(storageService.load(report.getStorageKey())).thenReturn(new ByteArrayInputStream(tamperedBytes));

        assertThatThrownBy(() -> reportService.downloadReport(report.getPublicId(), principalA, httpRequest))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("integrity check failed");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
