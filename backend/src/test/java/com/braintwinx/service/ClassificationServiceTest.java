package com.braintwinx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.braintwinx.audit.AuditService;
import com.braintwinx.client.AiClassificationResponse;
import com.braintwinx.client.AiServiceClient;
import com.braintwinx.dto.AnalysisJobResponse;
import com.braintwinx.dto.PredictionResponse;
import com.braintwinx.dto.ScanStatusResponse;
import com.braintwinx.entity.AnalysisJob;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.JobStatus;
import com.braintwinx.entity.ModelStatus;
import com.braintwinx.entity.ModelType;
import com.braintwinx.entity.ModelVersion;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.Prediction;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.ScanStatus;
import com.braintwinx.entity.ScanType;
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
import com.braintwinx.entity.PatientSex;
import com.braintwinx.repository.UserRepository;
import com.braintwinx.security.JwtService;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClassificationService")
class ClassificationServiceTest {

    @Mock
    private ScanRepository scanRepository;

    @Mock
    private AnalysisJobRepository analysisJobRepository;

    @Mock
    private PredictionRepository predictionRepository;

    @Mock
    private ModelVersionRepository modelVersionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private AiServiceClient aiServiceClient;

    @Mock
    private AuditService auditService;

    private AnalysisJobMapper analysisJobMapper;
    private PredictionMapper predictionMapper;
    private ObjectMapper objectMapper;
    private ClassificationService classificationService;

    private User doctorA;
    private User doctorB;
    private User researcher;
    private Patient patientA;
    private Scan scanA;
    private JwtService.AuthenticatedPrincipal principalA;
    private JwtService.AuthenticatedPrincipal principalB;
    private JwtService.AuthenticatedPrincipal principalResearcher;
    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        analysisJobMapper = new AnalysisJobMapper();
        predictionMapper = new PredictionMapper(objectMapper);

        classificationService = new ClassificationService(
                scanRepository,
                analysisJobRepository,
                predictionRepository,
                modelVersionRepository,
                userRepository,
                storageService,
                aiServiceClient,
                auditService,
                analysisJobMapper,
                predictionMapper,
                objectMapper
        );

        doctorA = new User("dr_smith", "dr_smith@hospital.org", "hash", "Dr Smith", Role.DOCTOR);
        setField(doctorA, "id", 1L);

        doctorB = new User("dr_jones", "dr_jones@hospital.org", "hash", "Dr Jones", Role.DOCTOR);
        setField(doctorB, "id", 2L);

        researcher = new User("res_alice", "alice@hospital.org", "hash", "Alice", Role.RESEARCHER);
        setField(researcher, "id", 3L);

        patientA = new Patient("PAT-001", (short) 1985, PatientSex.FEMALE, doctorA);
        setField(patientA, "id", 10L);

        scanA = new Scan(patientA, LocalDate.of(2026, 1, 15), ScanType.MRI_T1,
                "scans/storage-key.png", "image/png", 1024L, "sha256hash", doctorA);
        setField(scanA, "id", 100L);

        principalA = new JwtService.AuthenticatedPrincipal(doctorA.getPublicId(), Role.DOCTOR);
        principalB = new JwtService.AuthenticatedPrincipal(doctorB.getPublicId(), Role.DOCTOR);
        principalResearcher = new JwtService.AuthenticatedPrincipal(researcher.getPublicId(), Role.RESEARCHER);

        httpRequest = new MockHttpServletRequest();
    }

    @Nested
    @DisplayName("Trigger Analysis — Caseload Scope and Idempotency")
    class TriggerAnalysisScopeTest {

        @Test
        @DisplayName("doctor B accessing doctor A's scan returns 404, not 403 (IDOR protection)")
        void doctorOutOfCaseloadThrowsNotFound() {
            when(userRepository.findByPublicId(doctorB.getPublicId())).thenReturn(Optional.of(doctorB));
            when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

            assertThatThrownBy(() -> classificationService.triggerAnalysis(
                    scanA.getPublicId(), "key-123", principalB, httpRequest))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(scanA.getPublicId());

            verify(analysisJobRepository, never()).save(any());
        }

        @Test
        @DisplayName("researcher cannot trigger analysis (write access required)")
        void researcherCannotTriggerAnalysis() {
            when(userRepository.findByPublicId(researcher.getPublicId())).thenReturn(Optional.of(researcher));

            assertThatThrownBy(() -> classificationService.triggerAnalysis(
                    scanA.getPublicId(), "key-123", principalResearcher, httpRequest))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ApiErrorCode.FORBIDDEN));

            verify(analysisJobRepository, never()).save(any());
        }

        @Test
        @DisplayName("repeated request with same idempotency key returns existing job without duplicate work")
        void idempotencyHitReturnsExistingJob() {
            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

            AnalysisJob existingJob = new AnalysisJob(scanA, "idemp-key-1", doctorA, Instant.now());
            when(analysisJobRepository.findByScanAndIdempotencyKey(scanA, "idemp-key-1"))
                    .thenReturn(Optional.of(existingJob));

            AnalysisJobResponse response = classificationService.triggerAnalysis(
                    scanA.getPublicId(), "idemp-key-1", principalA, httpRequest);

            assertThat(response.idempotencyKey()).isEqualTo("idemp-key-1");
            assertThat(response.scanPublicId()).isEqualTo(scanA.getPublicId());
            verify(analysisJobRepository, never()).save(any());
        }

        @Test
        @DisplayName("fresh request advances scan through state machine and creates queued job")
        void freshRequestCreatesJobAndQueuesScan() {
            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));
            when(analysisJobRepository.findByScanAndIdempotencyKey(eq(scanA), anyString()))
                    .thenReturn(Optional.empty());

            when(analysisJobRepository.save(any(AnalysisJob.class))).thenAnswer(inv -> {
                AnalysisJob j = inv.getArgument(0);
                setField(j, "id", 500L);
                return j;
            });

            AnalysisJobResponse response = classificationService.triggerAnalysis(
                    scanA.getPublicId(), "new-key", principalA, httpRequest);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(JobStatus.QUEUED);
            assertThat(scanA.getStatus()).isEqualTo(ScanStatus.QUEUED);

            verify(auditService).record(eq(doctorA), eq("dr_smith"), eq(AuditAction.ANALYSIS_STARTED),
                    eq("Scan"), eq(scanA.getPublicId()), eq(true), any(), eq(httpRequest));
        }
    }

    @Nested
    @DisplayName("Inference Pipeline Execution — Fail-Closed and Persistence")
    class InferencePipelineTest {

        @Test
        @DisplayName("when AI service is unavailable, pipeline fails closed and writes NO prediction row")
        void aiServiceUnavailableFailsClosedWithoutPrediction() throws Exception {
            AnalysisJob job = new AnalysisJob(scanA, "key-fail", doctorA, Instant.now());
            setField(job, "id", 700L);
            scanA.transitionTo(ScanStatus.VALIDATING);
            scanA.transitionTo(ScanStatus.VALIDATED);
            scanA.transitionTo(ScanStatus.QUEUED);

            when(analysisJobRepository.findById(700L)).thenReturn(Optional.of(job));
            org.mockito.Mockito.doThrow(new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE, "Weights missing"))
                    .when(aiServiceClient).ensureReady();

            assertThatThrownBy(() -> classificationService.runInferencePipeline(700L))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ApiErrorCode.AI_SERVICE_UNAVAILABLE));

            assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
            assertThat(job.getErrorCode()).isEqualTo("AI_SERVICE_UNAVAILABLE");
            assertThat(scanA.getStatus()).isEqualTo(ScanStatus.FAILED);

            // CRITICAL MEDICAL SAFETY ASSERTION: NO prediction persisted
            verify(predictionRepository, never()).save(any());
            verify(auditService).record(eq(doctorA), eq("dr_smith"), eq(AuditAction.ANALYSIS_FAILED),
                    eq("Scan"), eq(scanA.getPublicId()), eq(false), any(), eq(null));
        }

        @Test
        @DisplayName("successful inference saves prediction with full distribution, links model, and succeeds scan")
        void successfulInferenceSavesPredictionAndCompletesScan() throws Exception {
            AnalysisJob job = new AnalysisJob(scanA, "key-ok", doctorA, Instant.now());
            setField(job, "id", 800L);
            scanA.transitionTo(ScanStatus.VALIDATING);
            scanA.transitionTo(ScanStatus.VALIDATED);
            scanA.transitionTo(ScanStatus.QUEUED);

            when(analysisJobRepository.findById(800L)).thenReturn(Optional.of(job));
            when(storageService.load(scanA.getStorageKey()))
                    .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));

            AiClassificationResponse aiResp = new AiClassificationResponse(
                    "BrainTumorCNN",
                    "1.0.0",
                    "1.0.0",
                    "meningioma",
                    new BigDecimal("0.94210"),
                    Map.of("glioma", new BigDecimal("0.02100"),
                            "meningioma", new BigDecimal("0.94210"),
                            "pituitary", new BigDecimal("0.03000"),
                            "no_tumor", new BigDecimal("0.00690")),
                    false
            );
            when(aiServiceClient.classify(eq(scanA.getPublicId()), eq("PAT-001"), any()))
                    .thenReturn(aiResp);

            ModelVersion mv = new ModelVersion("BrainTumorCNN", ModelType.CLASSIFIER, "1.0.0", "PyTorch", "1.0.0");
            when(modelVersionRepository.findByModelNameAndModelVersion("BrainTumorCNN", "1.0.0"))
                    .thenReturn(Optional.of(mv));

            when(predictionRepository.save(any(Prediction.class))).thenAnswer(inv -> {
                Prediction p = inv.getArgument(0);
                setField(p, "id", 999L);
                return p;
            });

            Prediction saved = classificationService.runInferencePipeline(800L);

            assertThat(saved).isNotNull();
            assertThat(saved.getPredictedClass()).isEqualTo("meningioma");
            assertThat(saved.getConfidence()).isEqualTo(new BigDecimal("0.94210"));
            assertThat(saved.isSynthetic()).isFalse();
            assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(scanA.getStatus()).isEqualTo(ScanStatus.COMPLETED);

            verify(predictionRepository).save(any(Prediction.class));
            verify(auditService).record(eq(doctorA), eq("dr_smith"), eq(AuditAction.ANALYSIS_COMPLETED),
                    eq("Scan"), eq(scanA.getPublicId()), eq(true), any(), eq(null));
        }
    }

    @Nested
    @DisplayName("Prediction and Status Queries")
    class QueriesTest {

        @Test
        @DisplayName("getPrediction returns latest prediction when present")
        void getPredictionReturnsLatest() throws Exception {
            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

            ModelVersion mv = new ModelVersion("BrainTumorCNN", ModelType.CLASSIFIER, "1.0.0", "PyTorch", "1.0.0");
            Prediction p = new Prediction(scanA, null, "glioma", new BigDecimal("0.89000"),
                    "{\"glioma\":0.89}", mv, "1.0.0", Instant.now(), false);

            when(predictionRepository.findFirstByScanOrderByCreatedAtDesc(scanA)).thenReturn(Optional.of(p));

            PredictionResponse res = classificationService.getPrediction(scanA.getPublicId(), principalA);
            assertThat(res.predictedClass()).isEqualTo("glioma");
            assertThat(res.confidence()).isEqualTo(new BigDecimal("0.89000"));
        }

        @Test
        @DisplayName("getScanStatus returns scan status and latest job info")
        void getScanStatusReturnsInfo() {
            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

            AnalysisJob job = new AnalysisJob(scanA, "key", doctorA, Instant.now());
            when(analysisJobRepository.findFirstByScanOrderByCreatedAtDesc(scanA)).thenReturn(Optional.of(job));

            ScanStatusResponse status = classificationService.getScanStatus(scanA.getPublicId(), principalA);
            assertThat(status.scanPublicId()).isEqualTo(scanA.getPublicId());
            assertThat(status.scanStatus()).isEqualTo(ScanStatus.UPLOADED);
            assertThat(status.jobStatus()).isEqualTo(JobStatus.QUEUED);
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        Field f = null;
        while (clazz != null) {
            try {
                f = clazz.getDeclaredField(fieldName);
                break;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (f == null) {
            throw new NoSuchFieldException(fieldName);
        }
        f.setAccessible(true);
        f.set(target, value);
    }
}
