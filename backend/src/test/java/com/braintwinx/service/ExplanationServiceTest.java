package com.braintwinx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.braintwinx.audit.AuditService;
import com.braintwinx.dto.ExplanationResponse;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.ExplanationStatus;
import com.braintwinx.entity.ModelStatus;
import com.braintwinx.entity.ModelType;
import com.braintwinx.entity.ModelVersion;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.PatientSex;
import com.braintwinx.entity.Prediction;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.ScanType;
import com.braintwinx.entity.SegmentationResult;
import com.braintwinx.entity.User;
import com.braintwinx.exception.ApiException;
import com.braintwinx.exception.ResourceNotFoundException;
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
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExplanationService")
class ExplanationServiceTest {

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
    private AuditService auditService;

    @Mock
    private HttpServletRequest httpRequest;

    private ExplanationValidator explanationValidator;
    private StubExplanationProvider stubExplanationProvider;
    private LlmExplanationProvider llmExplanationProvider;
    private ExplanationService explanationService;

    private User doctorA;
    private User doctorB;
    private Patient patientA;
    private Scan scanA;
    private ModelVersion classifierModel;
    private ModelVersion segmenterModel;
    private JwtService.AuthenticatedPrincipal principalA;
    private JwtService.AuthenticatedPrincipal principalB;

    @BeforeEach
    void setUp() throws Exception {
        explanationValidator = new ExplanationValidator();
        stubExplanationProvider = new StubExplanationProvider();
        llmExplanationProvider = new LlmExplanationProvider("", "clinical-llm-v1", "");

        explanationService = new ExplanationService(
                scanRepository,
                patientRepository,
                predictionRepository,
                segmentationResultRepository,
                growthPredictionRepository,
                userRepository,
                auditService,
                explanationValidator,
                stubExplanationProvider,
                llmExplanationProvider,
                "STUB"
        );

        doctorA = new User("dr_smith", "dr_smith@hospital.org", "hash", "Dr Smith", Role.DOCTOR);
        setField(doctorA, "id", 1L);

        doctorB = new User("dr_jones", "dr_jones@hospital.org", "hash", "Dr Jones", Role.DOCTOR);
        setField(doctorB, "id", 2L);

        principalA = new JwtService.AuthenticatedPrincipal(doctorA.getPublicId(), doctorA.getRole());
        principalB = new JwtService.AuthenticatedPrincipal(doctorB.getPublicId(), doctorB.getRole());

        patientA = new Patient("PT-1001", (short) 1980, PatientSex.FEMALE, doctorA);
        setField(patientA, "id", 10L);

        scanA = new Scan(patientA, LocalDate.now(), ScanType.MRI_T1C, "scans/scan-1.png", "image/png", 1024L, "sha256", doctorA);
        setField(scanA, "id", 20L);

        classifierModel = new ModelVersion("BrainTumorCNN", ModelType.CLASSIFIER, "1.0.0", "PyTorch", "1.0.0");
        segmenterModel = new ModelVersion("BrainTumorUNet", ModelType.SEGMENTER, "1.0.0", "PyTorch", "1.0.0");
    }

    @Test
    @DisplayName("Successfully generates compliant decision-support explanation with stub provider")
    void generateExplanationSuccess() {
        when(userRepository.findByPublicId(principalA.publicId())).thenReturn(Optional.of(doctorA));
        when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

        Prediction prediction = new Prediction(
                scanA,
                null,
                "GLIOMA",
                new BigDecimal("0.96"),
                "{\"GLIOMA\": 0.96}",
                classifierModel,
                "1.0.0",
                Instant.now(),
                false
        );
        when(predictionRepository.findFirstByScanOrderByCreatedAtDesc(scanA)).thenReturn(Optional.of(prediction));

        SegmentationResult seg = SegmentationResult.detected(
                scanA,
                null,
                "masks/mask-1.png",
                1250L,
                224,
                224,
                segmenterModel,
                "1.0.0",
                Instant.now(),
                false
        );
        when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(scanA)).thenReturn(Optional.of(seg));

        when(growthPredictionRepository.findFirstByPatientOrderByCreatedAtDesc(patientA)).thenReturn(Optional.empty());

        ExplanationResponse response = explanationService.generateExplanation(scanA.getPublicId(), principalA, httpRequest);

        assertThat(response.status()).isEqualTo(ExplanationStatus.INCLUDED);
        assertThat(response.patientCode()).isEqualTo(patientA.getPatientCode());
        assertThat(response.explanationText()).contains("GLIOMA");
        assertThat(response.explanationText()).contains("1250 pixels");
        assertThat(response.explanationText()).contains("Radiologist review");
        assertThat(response.rejectionReason()).isNull();

        verify(auditService).record(
                eq(doctorA),
                eq(doctorA.getUsername()),
                eq(AuditAction.EXPLANATION_GENERATED),
                eq("SCAN"),
                eq(scanA.getPublicId()),
                eq(true),
                any(),
                eq(httpRequest)
        );
    }

    @Test
    @DisplayName("Rejects out-of-scope caseload access with 404 (IDOR defense)")
    void rejectsOutOfScopeAccess() {
        when(userRepository.findByPublicId(principalB.publicId())).thenReturn(Optional.of(doctorB));
        when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

        assertThatThrownBy(() -> explanationService.generateExplanation(scanA.getPublicId(), principalB, httpRequest))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Throws UNAUTHORIZED when principal is missing")
    void throwsWhenPrincipalNull() {
        assertThatThrownBy(() -> explanationService.generateExplanation(scanA.getPublicId(), null, httpRequest))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No authenticated principal");
    }

    @Test
    @DisplayName("LLM provider fails closed when unconfigured and returns UNAVAILABLE")
    void llmFailsClosedWhenUnconfigured() {
        ExplanationService llmService = new ExplanationService(
                scanRepository,
                patientRepository,
                predictionRepository,
                segmentationResultRepository,
                growthPredictionRepository,
                userRepository,
                auditService,
                explanationValidator,
                stubExplanationProvider,
                llmExplanationProvider,
                "LLM"
        );

        when(userRepository.findByPublicId(principalA.publicId())).thenReturn(Optional.of(doctorA));
        when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));
        when(predictionRepository.findFirstByScanOrderByCreatedAtDesc(scanA)).thenReturn(Optional.empty());
        when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(scanA)).thenReturn(Optional.empty());
        when(growthPredictionRepository.findFirstByPatientOrderByCreatedAtDesc(patientA)).thenReturn(Optional.empty());

        ExplanationResponse response = llmService.generateExplanation(scanA.getPublicId(), principalA, httpRequest);

        assertThat(response.status()).isEqualTo(ExplanationStatus.UNAVAILABLE);
        assertThat(response.explanationText()).isNull();
        assertThat(response.rejectionReason()).contains("LLM_PROVIDER_NOT_CONFIGURED");

        verify(auditService).record(
                eq(doctorA),
                eq(doctorA.getUsername()),
                eq(AuditAction.EXPLANATION_GENERATED),
                eq("SCAN"),
                eq(scanA.getPublicId()),
                eq(true),
                any(),
                eq(httpRequest)
        );
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
