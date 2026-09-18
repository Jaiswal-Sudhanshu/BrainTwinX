package com.braintwinx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.braintwinx.audit.AuditService;
import com.braintwinx.client.AiServiceClient;
import com.braintwinx.client.AiSegmentationResponse;
import com.braintwinx.dto.SegmentationResponse;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.ModelStatus;
import com.braintwinx.entity.ModelType;
import com.braintwinx.entity.ModelVersion;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.PatientSex;
import com.braintwinx.entity.Prediction;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.ScanStatus;
import com.braintwinx.entity.ScanType;
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
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
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
@DisplayName("SegmentationService")
class SegmentationServiceTest {

    @Mock
    private ScanRepository scanRepository;

    @Mock
    private SegmentationResultRepository segmentationResultRepository;

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

    private SegmentationMapper segmentationMapper;
    private SegmentationService segmentationService;

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
        segmentationMapper = new SegmentationMapper();

        segmentationService = new SegmentationService(
                scanRepository,
                segmentationResultRepository,
                predictionRepository,
                modelVersionRepository,
                userRepository,
                storageService,
                aiServiceClient,
                auditService,
                segmentationMapper
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
    @DisplayName("Caseload Scope and Authorization")
    class ScopeTests {

        @Test
        @DisplayName("doctor B accessing doctor A's scan returns 404, not 403 (IDOR protection)")
        void doctorOutOfCaseloadThrowsNotFound() {
            when(userRepository.findByPublicId(doctorB.getPublicId())).thenReturn(Optional.of(doctorB));
            when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

            assertThatThrownBy(() -> segmentationService.segmentScanDirect(
                    scanA.getPublicId(), principalB, httpRequest))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(scanA.getPublicId());

            verify(segmentationResultRepository, never()).save(any());
        }

        @Test
        @DisplayName("researcher cannot trigger segmentation (write access forbidden)")
        void researcherCannotTriggerSegmentation() {
            when(userRepository.findByPublicId(researcher.getPublicId())).thenReturn(Optional.of(researcher));

            assertThatThrownBy(() -> segmentationService.segmentScanDirect(
                    scanA.getPublicId(), principalResearcher, httpRequest))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ApiErrorCode.FORBIDDEN));

            verify(segmentationResultRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Segmentation Execution & Safety")
    class ExecutionTests {

        @Test
        @DisplayName("fails closed when AI service is unavailable without saving mask or entity")
        void failsClosedWhenAiUnavailable() {
            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

            doThrow(new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE, "Weights missing"))
                    .when(aiServiceClient).ensureReady();

            assertThatThrownBy(() -> segmentationService.segmentScanDirect(
                    scanA.getPublicId(), principalA, httpRequest))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ApiErrorCode.AI_SERVICE_UNAVAILABLE));

            verify(storageService, never()).store(any(), anyString());
            verify(segmentationResultRepository, never()).save(any());
        }

        @Test
        @DisplayName("successful segmentation stores mask as artefact and links model and scan")
        void successfulSegmentationSavesResultAndMask() {
            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

            byte[] scanBytes = new byte[]{10, 20, 30, 40};
            when(storageService.load(scanA.getStorageKey())).thenReturn(new ByteArrayInputStream(scanBytes));

            byte[] maskBytes = new byte[]{(byte) 255, (byte) 255, (byte) 255};
            String maskB64 = Base64.getEncoder().encodeToString(maskBytes);

            AiSegmentationResponse aiResp = new AiSegmentationResponse(
                    "BrainTumorUNet",
                    "1.0.0",
                    "1.0.0",
                    true,
                    1500L,
                    224,
                    224,
                    50,
                    60,
                    30,
                    40,
                    maskB64,
                    false
            );
            when(aiServiceClient.segment(eq(scanA.getPublicId()), eq("PAT-001"), any()))
                    .thenReturn(aiResp);

            ModelVersion mv = new ModelVersion("BrainTumorUNet", ModelType.SEGMENTER, "1.0.0", "PyTorch", "1.0.0");
            when(modelVersionRepository.findByModelNameAndModelVersion("BrainTumorUNet", "1.0.0"))
                    .thenReturn(Optional.of(mv));

            when(segmentationResultRepository.save(any(SegmentationResult.class))).thenAnswer(inv -> inv.getArgument(0));

            SegmentationResponse response = segmentationService.segmentScanDirect(
                    scanA.getPublicId(), principalA, httpRequest);

            assertThat(response).isNotNull();
            assertThat(response.tumorDetected()).isTrue();
            assertThat(response.tumorAreaPx()).isEqualTo(1500L);
            assertThat(response.bboxX()).isEqualTo(50);
            assertThat(response.bboxY()).isEqualTo(60);
            assertThat(response.bboxWidth()).isEqualTo(30);
            assertThat(response.bboxHeight()).isEqualTo(40);
            assertThat(response.hasMaskFile()).isTrue();
            assertThat(scanA.getStatus()).isEqualTo(ScanStatus.COMPLETED);

            // Verify mask stored via storageService
            verify(storageService).store(any(InputStream.class), anyString());
            verify(segmentationResultRepository).save(any(SegmentationResult.class));
            verify(auditService).record(eq(doctorA), eq("dr_smith"), eq(AuditAction.ANALYSIS_COMPLETED),
                    eq("Scan"), eq(scanA.getPublicId()), eq(true), any(), eq(httpRequest));
        }

        @Test
        @DisplayName("segmentation with no tumor detected sets null area, null bbox, and no mask stored")
        void segmentationNoTumorDetected() {
            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

            byte[] scanBytes = new byte[]{10, 20, 30, 40};
            when(storageService.load(scanA.getStorageKey())).thenReturn(new ByteArrayInputStream(scanBytes));

            AiSegmentationResponse aiResp = new AiSegmentationResponse(
                    "BrainTumorUNet",
                    "1.0.0",
                    "1.0.0",
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false
            );
            when(aiServiceClient.segment(eq(scanA.getPublicId()), eq("PAT-001"), any()))
                    .thenReturn(aiResp);

            ModelVersion mv = new ModelVersion("BrainTumorUNet", ModelType.SEGMENTER, "1.0.0", "PyTorch", "1.0.0");
            when(modelVersionRepository.findByModelNameAndModelVersion("BrainTumorUNet", "1.0.0"))
                    .thenReturn(Optional.of(mv));

            when(segmentationResultRepository.save(any(SegmentationResult.class))).thenAnswer(inv -> inv.getArgument(0));

            SegmentationResponse response = segmentationService.segmentScanDirect(
                    scanA.getPublicId(), principalA, httpRequest);

            assertThat(response.tumorDetected()).isFalse();
            assertThat(response.tumorAreaPx()).isNull();
            assertThat(response.bboxX()).isNull();
            assertThat(response.hasMaskFile()).isFalse();

            // Assert NO mask stored
            verify(storageService, never()).store(any(), anyString());
        }
    }

    @Nested
    @DisplayName("Queries and Mask Download")
    class QueryTests {

        @Test
        @DisplayName("getSegmentation returns latest result")
        void getSegmentationReturnsLatest() {
            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

            ModelVersion mv = new ModelVersion("BrainTumorUNet", ModelType.SEGMENTER, "1.0.0", "PyTorch", "1.0.0");
            SegmentationResult res = SegmentationResult.notDetected(scanA, null, mv, "1.0.0", Instant.now(), false);

            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(scanA)).thenReturn(Optional.of(res));

            SegmentationResponse dto = segmentationService.getSegmentation(scanA.getPublicId(), principalA);
            assertThat(dto).isNotNull();
            assertThat(dto.tumorDetected()).isFalse();
        }

        @Test
        @DisplayName("loadMask throws 404 when no mask file exists")
        void loadMaskThrowsNotFoundWhenNoMask() {
            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(scanRepository.findWithPatientByPublicId(scanA.getPublicId())).thenReturn(Optional.of(scanA));

            ModelVersion mv = new ModelVersion("BrainTumorUNet", ModelType.SEGMENTER, "1.0.0", "PyTorch", "1.0.0");
            SegmentationResult res = SegmentationResult.notDetected(scanA, null, mv, "1.0.0", Instant.now(), false);

            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(scanA)).thenReturn(Optional.of(res));

            assertThatThrownBy(() -> segmentationService.loadMask(scanA.getPublicId(), principalA))
                    .isInstanceOf(ResourceNotFoundException.class);
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
