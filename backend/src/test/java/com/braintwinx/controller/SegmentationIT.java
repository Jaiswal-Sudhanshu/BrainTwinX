package com.braintwinx.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.braintwinx.AbstractIntegrationTest;
import com.braintwinx.client.AiSegmentationResponse;
import com.braintwinx.client.AiServiceClient;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.User;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import com.braintwinx.repository.ScanRepository;
import com.braintwinx.repository.SegmentationResultRepository;
import com.braintwinx.repository.UserRepository;
import com.braintwinx.security.JwtService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@DisplayName("MRI U-Net Segmentation Integration Tests")
class SegmentationIT extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ScanRepository scanRepository;

    @Autowired
    private SegmentationResultRepository segmentationResultRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.braintwinx.config.CorrelationIdFilter correlationIdFilter;

    @MockitoBean
    private AiServiceClient aiServiceClient;

    private MockMvc mockMvc;
    private String doctorAToken;
    private String doctorBToken;
    private String researcherToken;

    @BeforeEach
    void setUp() {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                    .addFilters(correlationIdFilter)
                    .apply(org.springframework.security.test.web.servlet.setup
                            .SecurityMockMvcConfigurers.springSecurity())
                    .build();
        }
        doctorAToken = tokenFor(createUser(Role.DOCTOR));
        doctorBToken = tokenFor(createUser(Role.DOCTOR));
        researcherToken = tokenFor(createUser(Role.RESEARCHER));
    }

    private byte[] createSampleImage() throws IOException {
        BufferedImage image = new BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return baos.toByteArray();
    }

    private String createPatient(String token) throws Exception {
        String patientCode = "PAT-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {
                    "patientCode": "%s",
                    "birthYear": 1980,
                    "sex": "FEMALE"
                }
                """.formatted(patientCode);

        mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        return patientCode;
    }

    private String uploadScan(String token, String patientCode) throws Exception {
        byte[] imageBytes = createSampleImage();
        MockMultipartFile file = new MockMultipartFile(
                "file", "brain_mri.png", "image/png", imageBytes);

        MvcResult result = mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                        .file(file)
                        .param("scanDate", "2026-01-20")
                        .param("scanType", "MRI_T1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.read(responseBody, "$.publicId");
    }

    @Nested
    @DisplayName("Caseload Scope")
    class ScopeTests {

        @Test
        @DisplayName("doctor B cannot trigger segmentation on doctor A's scan (404 IDOR)")
        void doctorOutOfCaseloadReturns404() throws Exception {
            String patientCode = createPatient(doctorAToken);
            String scanId = uploadScan(doctorAToken, patientCode);

            mockMvc.perform(post("/api/v1/scans/" + scanId + "/segment")
                            .header("Authorization", "Bearer " + doctorBToken))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Segmentation Pipeline Execution")
    class PipelineTests {

        @Test
        @DisplayName("segmentation fails closed when AI service is unavailable (503 and NO segmentation row)")
        void failsClosedWhenAiUnavailable() throws Exception {
            String patientCode = createPatient(doctorAToken);
            String scanId = uploadScan(doctorAToken, patientCode);

            doThrow(new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE, "Weights missing"))
                    .when(aiServiceClient).ensureReady();

            mockMvc.perform(post("/api/v1/scans/" + scanId + "/segment")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.errorCode").value("AI_SERVICE_UNAVAILABLE"));

            // Check that scan has status FAILED
            mockMvc.perform(get("/api/v1/scans/" + scanId + "/status")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scanStatus").value("FAILED"))
                    .andExpect(jsonPath("$.failureCode").value("AI_SERVICE_UNAVAILABLE"));

            // Assert NO segmentation exists
            mockMvc.perform(get("/api/v1/scans/" + scanId + "/segmentation")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("successful segmentation persists mask artefact, records entity, and supports mask download")
        void segmentationSucceedsAndSupportsDownload() throws Exception {
            String patientCode = createPatient(doctorAToken);
            String scanId = uploadScan(doctorAToken, patientCode);

            doNothing().when(aiServiceClient).ensureReady();

            byte[] maskBytes = createSampleImage();
            String maskB64 = Base64.getEncoder().encodeToString(maskBytes);

            AiSegmentationResponse aiResp = new AiSegmentationResponse(
                    "BrainTumorUNet",
                    "1.0.0",
                    "1.0.0",
                    true,
                    1200L,
                    224,
                    224,
                    45,
                    55,
                    35,
                    45,
                    maskB64,
                    false
            );
            when(aiServiceClient.segment(anyString(), anyString(), any())).thenReturn(aiResp);

            mockMvc.perform(post("/api/v1/scans/" + scanId + "/segment")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tumorDetected").value(true))
                    .andExpect(jsonPath("$.tumorAreaPx").value(1200))
                    .andExpect(jsonPath("$.bboxX").value(45))
                    .andExpect(jsonPath("$.bboxY").value(55))
                    .andExpect(jsonPath("$.bboxWidth").value(35))
                    .andExpect(jsonPath("$.bboxHeight").value(45))
                    .andExpect(jsonPath("$.hasMaskFile").value(true))
                    .andExpect(jsonPath("$.modelName").value("BrainTumorUNet"))
                    .andExpect(jsonPath("$.modelVersion").value("1.0.0"))
                    .andExpect(jsonPath("$.isSynthetic").value(false));

            // Verify GET /scans/{id}/segmentation
            mockMvc.perform(get("/api/v1/scans/" + scanId + "/segmentation")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tumorDetected").value(true))
                    .andExpect(jsonPath("$.tumorAreaPx").value(1200));

            // Verify GET /scans/{id}/mask downloads image/png
            mockMvc.perform(get("/api/v1/scans/" + scanId + "/mask")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG))
                    .andExpect(content().bytes(maskBytes));

            // Verify researcher can read segmentation
            mockMvc.perform(get("/api/v1/scans/" + scanId + "/segmentation")
                            .header("Authorization", "Bearer " + researcherToken))
                    .andExpect(status().isOk());

            // Verify IDOR: doctor B cannot view segmentation or download mask
            mockMvc.perform(get("/api/v1/scans/" + scanId + "/segmentation")
                            .header("Authorization", "Bearer " + doctorBToken))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/api/v1/scans/" + scanId + "/mask")
                            .header("Authorization", "Bearer " + doctorBToken))
                    .andExpect(status().isNotFound());
        }
    }

    private User createUser(Role role) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User u = new User("user_" + unique, "user_" + unique + "@hospital.org",
                passwordEncoder.encode("Password123!"), "Test User", role);
        return userRepository.save(u);
    }

    private String tokenFor(User user) {
        return jwtService.issueAccessToken(user, Instant.now());
    }
}
