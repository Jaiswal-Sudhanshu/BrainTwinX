package com.braintwinx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.braintwinx.audit.AuditService;
import com.braintwinx.client.AiLongitudinalForecastResponse;
import com.braintwinx.client.AiServiceClient;
import com.braintwinx.dto.GrowthPredictionResponse;
import com.braintwinx.entity.GrowthAnalysisStatus;
import com.braintwinx.entity.GrowthPrediction;
import com.braintwinx.entity.ModelStatus;
import com.braintwinx.entity.ModelType;
import com.braintwinx.entity.ModelVersion;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.PatientSex;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.ScanType;
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
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("GrowthPredictionService")
class GrowthPredictionServiceTest {

    @Mock
    private GrowthPredictionRepository growthPredictionRepository;

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private ScanRepository scanRepository;

    @Mock
    private SegmentationResultRepository segmentationResultRepository;

    @Mock
    private ModelVersionRepository modelVersionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiServiceClient aiServiceClient;

    @Mock
    private AuditService auditService;

    @Mock
    private HttpServletRequest httpRequest;

    private GrowthPredictionMapper growthPredictionMapper;
    private ObjectMapper objectMapper;
    private GrowthPredictionService growthPredictionService;

    private User doctorA;
    private User doctorB;
    private User researcher;
    private Patient patientA;
    private JwtService.AuthenticatedPrincipal principalA;
    private JwtService.AuthenticatedPrincipal principalB;
    private JwtService.AuthenticatedPrincipal principalResearcher;

    @BeforeEach
    void setUp() throws Exception {
        growthPredictionMapper = new GrowthPredictionMapper();
        objectMapper = new ObjectMapper();

        growthPredictionService = new GrowthPredictionService(
                growthPredictionRepository,
                patientRepository,
                scanRepository,
                segmentationResultRepository,
                modelVersionRepository,
                userRepository,
                aiServiceClient,
                auditService,
                growthPredictionMapper,
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

        principalA = new JwtService.AuthenticatedPrincipal(doctorA.getPublicId(), Role.DOCTOR);
        principalB = new JwtService.AuthenticatedPrincipal(doctorB.getPublicId(), Role.DOCTOR);
        principalResearcher = new JwtService.AuthenticatedPrincipal(researcher.getPublicId(), Role.RESEARCHER);
    }

    private Scan createScan(long id, LocalDate date) throws Exception {
        Scan s = new Scan(patientA, date, ScanType.MRI_T1, "scans/key-" + id, "image/png", 1024L, "hash" + id, doctorA);
        setField(s, "id", id);
        return s;
    }

    private SegmentationResult createSegmentation(long id, Scan scan, long areaPx) throws Exception {
        SegmentationResult seg = SegmentationResult.detected(
                scan, null, "masks/m" + id, areaPx, 224, 224,
                null, "1.0.0", Instant.now(), false
        );
        setField(seg, "id", id);
        return seg;
    }

    @Nested
    @DisplayName("Caseload Scope and Authorization")
    class ScopeTests {

        @Test
        @DisplayName("doctor out of caseload throws 404 (IDOR protection)")
        void doctorOutOfCaseloadThrowsNotFound() {
            when(userRepository.findByPublicId(doctorB.getPublicId())).thenReturn(Optional.of(doctorB));
            when(patientRepository.findByPatientCode(patientA.getPatientCode())).thenReturn(Optional.of(patientA));

            assertThatThrownBy(() -> growthPredictionService.analyzeGrowth(
                    patientA.getPatientCode(), principalB, httpRequest))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(patientA.getPatientCode());

            verify(growthPredictionRepository, never()).save(any());
            verify(aiServiceClient, never()).forecastGrowth(any());
        }

        @Test
        @DisplayName("researcher cannot trigger growth analysis (write access forbidden)")
        void researcherCannotTriggerAnalysis() {
            when(userRepository.findByPublicId(researcher.getPublicId())).thenReturn(Optional.of(researcher));

            assertThatThrownBy(() -> growthPredictionService.analyzeGrowth(
                    patientA.getPatientCode(), principalResearcher, httpRequest))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ApiErrorCode.FORBIDDEN));

            verify(growthPredictionRepository, never()).save(any());
            verify(aiServiceClient, never()).forecastGrowth(any());
        }
    }

    @Nested
    @DisplayName("Preconditions & INSUFFICIENT_HISTORY Policy")
    class PreconditionTests {

        @Test
        @DisplayName("0 observations yields INSUFFICIENT_HISTORY with zero AI calls")
        void zeroObservationsYieldsInsufficientHistory() {
            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(patientRepository.findByPatientCode(patientA.getPatientCode())).thenReturn(Optional.of(patientA));
            when(scanRepository.findByPatientOrderByScanDateAsc(patientA)).thenReturn(List.of());
            when(growthPredictionRepository.save(any(GrowthPrediction.class))).thenAnswer(i -> i.getArgument(0));

            GrowthPredictionResponse res = growthPredictionService.analyzeGrowth(
                    patientA.getPatientCode(), principalA, httpRequest
            );

            assertThat(res.status()).isEqualTo(GrowthAnalysisStatus.INSUFFICIENT_HISTORY);
            assertThat(res.observationCount()).isEqualTo(0);
            assertThat(res.spanDays()).isNull();
            assertThat(res.trendDirection()).isNull();
            assertThat(res.forecastJson()).isNull();
            assertThat(res.modelName()).isNull();

            verify(aiServiceClient, never()).forecastGrowth(any());
        }

        @Test
        @DisplayName("1 observation yields INSUFFICIENT_HISTORY with zero AI calls")
        void oneObservationYieldsInsufficientHistory() throws Exception {
            Scan s1 = createScan(101L, LocalDate.of(2026, 1, 1));
            SegmentationResult seg1 = createSegmentation(201L, s1, 1000L);

            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(patientRepository.findByPatientCode(patientA.getPatientCode())).thenReturn(Optional.of(patientA));
            when(scanRepository.findByPatientOrderByScanDateAsc(patientA)).thenReturn(List.of(s1));
            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(s1)).thenReturn(Optional.of(seg1));
            when(growthPredictionRepository.save(any(GrowthPrediction.class))).thenAnswer(i -> i.getArgument(0));

            GrowthPredictionResponse res = growthPredictionService.analyzeGrowth(
                    patientA.getPatientCode(), principalA, httpRequest
            );

            assertThat(res.status()).isEqualTo(GrowthAnalysisStatus.INSUFFICIENT_HISTORY);
            assertThat(res.observationCount()).isEqualTo(1);
            assertThat(res.spanDays()).isNull();
            assertThat(res.forecastJson()).isNull();

            verify(aiServiceClient, never()).forecastGrowth(any());
        }

        @Test
        @DisplayName("2 observations (n-1 below min 3) yields INSUFFICIENT_HISTORY with zero AI calls")
        void twoObservationsYieldsInsufficientHistory() throws Exception {
            Scan s1 = createScan(101L, LocalDate.of(2026, 1, 1));
            Scan s2 = createScan(102L, LocalDate.of(2026, 3, 1));
            SegmentationResult seg1 = createSegmentation(201L, s1, 1000L);
            SegmentationResult seg2 = createSegmentation(202L, s2, 1100L);

            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(patientRepository.findByPatientCode(patientA.getPatientCode())).thenReturn(Optional.of(patientA));
            when(scanRepository.findByPatientOrderByScanDateAsc(patientA)).thenReturn(List.of(s1, s2));
            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(s1)).thenReturn(Optional.of(seg1));
            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(s2)).thenReturn(Optional.of(seg2));
            when(growthPredictionRepository.save(any(GrowthPrediction.class))).thenAnswer(i -> i.getArgument(0));

            GrowthPredictionResponse res = growthPredictionService.analyzeGrowth(
                    patientA.getPatientCode(), principalA, httpRequest
            );

            assertThat(res.status()).isEqualTo(GrowthAnalysisStatus.INSUFFICIENT_HISTORY);
            assertThat(res.observationCount()).isEqualTo(2);
            assertThat(res.spanDays()).isEqualTo(59);
            assertThat(res.forecastJson()).isNull();
            assertThat(res.trendDirection()).isNull();

            verify(aiServiceClient, never()).forecastGrowth(any());
        }

        @Test
        @DisplayName("3 observations but span < 30 days yields INSUFFICIENT_HISTORY")
        void threeObservationsShortSpanYieldsInsufficientHistory() throws Exception {
            Scan s1 = createScan(101L, LocalDate.of(2026, 1, 1));
            Scan s2 = createScan(102L, LocalDate.of(2026, 1, 5));
            Scan s3 = createScan(103L, LocalDate.of(2026, 1, 10)); // span = 9 days
            SegmentationResult seg1 = createSegmentation(201L, s1, 1000L);
            SegmentationResult seg2 = createSegmentation(202L, s2, 1050L);
            SegmentationResult seg3 = createSegmentation(203L, s3, 1100L);

            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(patientRepository.findByPatientCode(patientA.getPatientCode())).thenReturn(Optional.of(patientA));
            when(scanRepository.findByPatientOrderByScanDateAsc(patientA)).thenReturn(List.of(s1, s2, s3));
            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(s1)).thenReturn(Optional.of(seg1));
            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(s2)).thenReturn(Optional.of(seg2));
            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(s3)).thenReturn(Optional.of(seg3));
            when(growthPredictionRepository.save(any(GrowthPrediction.class))).thenAnswer(i -> i.getArgument(0));

            GrowthPredictionResponse res = growthPredictionService.analyzeGrowth(
                    patientA.getPatientCode(), principalA, httpRequest
            );

            assertThat(res.status()).isEqualTo(GrowthAnalysisStatus.INSUFFICIENT_HISTORY);
            assertThat(res.observationCount()).isEqualTo(3);
            assertThat(res.spanDays()).isEqualTo(9);
            assertThat(res.forecastJson()).isNull();

            verify(aiServiceClient, never()).forecastGrowth(any());
        }
    }

    @Nested
    @DisplayName("Sufficient History & AI Execution")
    class ExecutionTests {

        @Test
        @DisplayName("fails closed when AI service is unavailable without fabricating trend")
        void failsClosedWhenAiUnavailable() throws Exception {
            Scan s1 = createScan(101L, LocalDate.of(2026, 1, 1));
            Scan s2 = createScan(102L, LocalDate.of(2026, 2, 1));
            Scan s3 = createScan(103L, LocalDate.of(2026, 3, 1));
            SegmentationResult seg1 = createSegmentation(201L, s1, 1000L);
            SegmentationResult seg2 = createSegmentation(202L, s2, 1050L);
            SegmentationResult seg3 = createSegmentation(203L, s3, 1100L);

            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(patientRepository.findByPatientCode(patientA.getPatientCode())).thenReturn(Optional.of(patientA));
            when(scanRepository.findByPatientOrderByScanDateAsc(patientA)).thenReturn(List.of(s1, s2, s3));
            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(s1)).thenReturn(Optional.of(seg1));
            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(s2)).thenReturn(Optional.of(seg2));
            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(s3)).thenReturn(Optional.of(seg3));

            when(aiServiceClient.forecastGrowth(any())).thenThrow(
                    new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE, "LSTM model weights not loaded")
            );

            assertThatThrownBy(() -> growthPredictionService.analyzeGrowth(
                    patientA.getPatientCode(), principalA, httpRequest))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ApiErrorCode.AI_SERVICE_UNAVAILABLE));

            verify(growthPredictionRepository).save(any(GrowthPrediction.class));
        }

        @Test
        @DisplayName("successful forecast produces COMPLETED prediction with disclaimer and attribution")
        void successfulForecastProducesCompletedPrediction() throws Exception {
            Scan s1 = createScan(101L, LocalDate.of(2026, 1, 1));
            Scan s2 = createScan(102L, LocalDate.of(2026, 2, 1));
            Scan s3 = createScan(103L, LocalDate.of(2026, 3, 1));
            SegmentationResult seg1 = createSegmentation(201L, s1, 1000L);
            SegmentationResult seg2 = createSegmentation(202L, s2, 1050L);
            SegmentationResult seg3 = createSegmentation(203L, s3, 1100L);

            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(patientRepository.findByPatientCode(patientA.getPatientCode())).thenReturn(Optional.of(patientA));
            when(scanRepository.findByPatientOrderByScanDateAsc(patientA)).thenReturn(List.of(s1, s2, s3));
            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(s1)).thenReturn(Optional.of(seg1));
            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(s2)).thenReturn(Optional.of(seg2));
            when(segmentationResultRepository.findFirstByScanOrderByCreatedAtDesc(s3)).thenReturn(Optional.of(seg3));

            ModelVersion mv = new ModelVersion("BrainTumorLSTM", ModelType.FORECASTER, "1.0.0", "PyTorch", "1.0.0");
            when(modelVersionRepository.findByModelNameAndModelVersion("BrainTumorLSTM", "1.0.0")).thenReturn(Optional.of(mv));

            AiLongitudinalForecastResponse.ForecastPointDto fp1 = new AiLongitudinalForecastResponse.ForecastPointDto(30, 1150L, 1035L, 1265L);
            AiLongitudinalForecastResponse.ForecastPointDto fp2 = new AiLongitudinalForecastResponse.ForecastPointDto(60, 1200L, 1080L, 1320L);
            AiLongitudinalForecastResponse.ForecastPointDto fp3 = new AiLongitudinalForecastResponse.ForecastPointDto(90, 1250L, 1125L, 1375L);

            AiLongitudinalForecastResponse aiResp = new AiLongitudinalForecastResponse(
                    "BrainTumorLSTM", "1.0.0", "1.0.0", "INCREASING",
                    List.of(fp1, fp2, fp3),
                    "MODEL-BASED TREND ESTIMATE. Not a clinical diagnosis or guarantee of future growth.",
                    false
            );
            when(aiServiceClient.forecastGrowth(any())).thenReturn(aiResp);
            when(growthPredictionRepository.save(any(GrowthPrediction.class))).thenAnswer(i -> i.getArgument(0));

            GrowthPredictionResponse res = growthPredictionService.analyzeGrowth(
                    patientA.getPatientCode(), principalA, httpRequest
            );

            assertThat(res.status()).isEqualTo(GrowthAnalysisStatus.COMPLETED);
            assertThat(res.observationCount()).isEqualTo(3);
            assertThat(res.spanDays()).isEqualTo(59);
            assertThat(res.trendDirection()).isEqualTo(TrendDirection.INCREASING);
            assertThat(res.modelName()).isEqualTo("BrainTumorLSTM");
            assertThat(res.modelVersion()).isEqualTo("1.0.0");
            assertThat(res.disclaimer()).contains("MODEL-BASED TREND ESTIMATE");
            assertThat(res.isSynthetic()).isFalse();
            assertThat(res.forecastJson()).contains("1150");
        }
    }

    @Nested
    @DisplayName("Queries")
    class QueryTests {

        @Test
        @DisplayName("getLatestGrowthPrediction returns latest result")
        void getLatestReturnsResult() {
            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(patientRepository.findByPatientCode(patientA.getPatientCode())).thenReturn(Optional.of(patientA));

            GrowthPrediction gp = GrowthPrediction.insufficientHistory(patientA, null, null, 1, null);
            when(growthPredictionRepository.findFirstByPatientOrderByCreatedAtDesc(patientA)).thenReturn(Optional.of(gp));

            GrowthPredictionResponse res = growthPredictionService.getLatestGrowthPrediction(patientA.getPatientCode(), principalA);
            assertThat(res.status()).isEqualTo(GrowthAnalysisStatus.INSUFFICIENT_HISTORY);
            assertThat(res.patientCode()).isEqualTo(patientA.getPatientCode());
        }

        @Test
        @DisplayName("getLatestGrowthPrediction throws 404 when none found")
        void getLatestThrowsNotFoundWhenNone() {
            when(userRepository.findByPublicId(doctorA.getPublicId())).thenReturn(Optional.of(doctorA));
            when(patientRepository.findByPatientCode(patientA.getPatientCode())).thenReturn(Optional.of(patientA));
            when(growthPredictionRepository.findFirstByPatientOrderByCreatedAtDesc(patientA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> growthPredictionService.getLatestGrowthPrediction(patientA.getPatientCode(), principalA))
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
