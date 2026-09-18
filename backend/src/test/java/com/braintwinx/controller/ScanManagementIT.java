package com.braintwinx.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.braintwinx.AbstractIntegrationTest;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.User;
import com.braintwinx.repository.UserRepository;
import com.braintwinx.security.JwtService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@DisplayName("MRI scan management and hostile-input validation")
class ScanManagementIT extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.braintwinx.config.CorrelationIdFilter correlationIdFilter;

    private MockMvc mockMvc;
    private String adminToken;
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
        adminToken = tokenFor(createUser(Role.ADMIN));
        doctorAToken = tokenFor(createUser(Role.DOCTOR));
        doctorBToken = tokenFor(createUser(Role.DOCTOR));
        researcherToken = tokenFor(createUser(Role.RESEARCHER));
    }

    private byte[] createSampleImage(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        return baos.toByteArray();
    }

    @Nested
    @DisplayName("valid uploads")
    class ValidUploads {

        @Test
        @DisplayName("doctor can upload a valid PNG scan and receives a 201 Location header")
        void doctorCanUploadPngScan() throws Exception {
            String patientCode = createPatient(doctorAToken);
            byte[] pngBytes = createSampleImage("png", 32, 32);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "scan_brain.png", "image/png", pngBytes);

            MvcResult result = mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                            .file(file)
                            .param("scanDate", "2026-08-20")
                            .param("scanType", "MRI_T1")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/scans/")))
                    .andExpect(jsonPath("$.patientCode").value(patientCode))
                    .andExpect(jsonPath("$.status").value("UPLOADED"))
                    .andExpect(jsonPath("$.detectedMimeType").value("image/png"))
                    .andExpect(jsonPath("$.imageWidth").value(32))
                    .andExpect(jsonPath("$.imageHeight").value(32))
                    .andExpect(jsonPath("$.fileSizeBytes").value(pngBytes.length))
                    .andExpect(jsonPath("$.originalFilename").value("scan_brain.png"))
                    .andReturn();

            String publicId = extractPublicId(result);

            // Verify persistence in MySQL
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM scans WHERE public_id = ? AND status = 'UPLOADED'",
                    Integer.class, publicId);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("doctor can upload a valid JPEG scan")
        void doctorCanUploadJpegScan() throws Exception {
            String patientCode = createPatient(doctorAToken);
            byte[] jpegBytes = createSampleImage("jpg", 40, 40);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "scan_brain.jpg", "image/jpeg", jpegBytes);

            mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                            .file(file)
                            .param("scanDate", "2026-08-21")
                            .param("scanType", "MRI_T2")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.detectedMimeType").value("image/jpeg"))
                    .andExpect(jsonPath("$.imageWidth").value(40))
                    .andExpect(jsonPath("$.imageHeight").value(40));
        }

        @Test
        @DisplayName("same image uploaded for two different patients is accepted")
        void sameImageDifferentPatientsAllowed() throws Exception {
            String patientA = createPatient(doctorAToken);
            String patientB = createPatient(doctorAToken);
            byte[] pngBytes = createSampleImage("png", 20, 20);

            MockMultipartFile fileA = new MockMultipartFile("file", "a.png", "image/png", pngBytes);
            MockMultipartFile fileB = new MockMultipartFile("file", "b.png", "image/png", pngBytes);

            mockMvc.perform(multipart("/api/v1/patients/" + patientA + "/scans")
                            .file(fileA)
                            .param("scanDate", "2026-08-20")
                            .param("scanType", "MRI_T1")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isCreated());

            mockMvc.perform(multipart("/api/v1/patients/" + patientB + "/scans")
                            .file(fileB)
                            .param("scanDate", "2026-08-20")
                            .param("scanType", "MRI_T1")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("hostile input and format rejection")
    class HostileRejection {

        @Test
        @DisplayName("duplicate scan for same patient is rejected with 409 Conflict")
        void duplicateScanRejected() throws Exception {
            String patientCode = createPatient(doctorAToken);
            byte[] pngBytes = createSampleImage("png", 24, 24);

            MockMultipartFile file = new MockMultipartFile("file", "scan.png", "image/png", pngBytes);

            mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                            .file(file)
                            .param("scanDate", "2026-08-20")
                            .param("scanType", "MRI_T1")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isCreated());

            mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                            .file(file)
                            .param("scanDate", "2026-08-22")
                            .param("scanType", "MRI_T1")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        }

        @Test
        @DisplayName("unsupported file extension is rejected with 415")
        void unsupportedExtensionRejected() throws Exception {
            String patientCode = createPatient(doctorAToken);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "scan.pdf", "application/pdf", "dummy pdf".getBytes(StandardCharsets.UTF_8));

            mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                            .file(file)
                            .param("scanDate", "2026-08-20")
                            .param("scanType", "MRI_T1")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_MRI_FORMAT"));
        }

        @Test
        @DisplayName("spoofed MIME type is rejected with 400")
        void spoofedMimeTypeRejected() throws Exception {
            String patientCode = createPatient(doctorAToken);
            byte[] pngBytes = createSampleImage("png", 16, 16);

            // PNG bytes declared as JPEG
            MockMultipartFile file = new MockMultipartFile(
                    "file", "scan.png", "image/jpeg", pngBytes);

            mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                            .file(file)
                            .param("scanDate", "2026-08-20")
                            .param("scanType", "MRI_T1")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_FILE"));
        }

        @Test
        @DisplayName("corrupt image data is rejected with 400")
        void corruptImageRejected() throws Exception {
            String patientCode = createPatient(doctorAToken);
            byte[] corruptBytes = new byte[] {
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02
            };
            MockMultipartFile file = new MockMultipartFile(
                    "file", "corrupt.png", "image/png", corruptBytes);

            mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                            .file(file)
                            .param("scanDate", "2026-08-20")
                            .param("scanType", "MRI_T1")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_FILE"));
        }

        @Test
        @DisplayName("empty file is rejected with 400")
        void emptyFileRejected() throws Exception {
            String patientCode = createPatient(doctorAToken);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "empty.png", "image/png", new byte[0]);

            mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                            .file(file)
                            .param("scanDate", "2026-08-20")
                            .param("scanType", "MRI_T1")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_FILE"));
        }
    }

    @Nested
    @DisplayName("caseload scoping and IDOR defence")
    class CaseloadScoping {

        @Test
        @DisplayName("doctor B uploading to doctor A's patient receives 404 (IDOR defence)")
        void doctorCannotUploadToAnotherDoctorPatient() throws Exception {
            String patientCode = createPatient(doctorAToken);
            byte[] pngBytes = createSampleImage("png", 16, 16);
            MockMultipartFile file = new MockMultipartFile("file", "scan.png", "image/png", pngBytes);

            mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                            .file(file)
                            .param("scanDate", "2026-08-20")
                            .param("scanType", "MRI_T1")
                            .header("Authorization", "Bearer " + doctorBToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));
        }

        @Test
        @DisplayName("doctor B reading doctor A's scan receives 404 (IDOR defence)")
        void doctorCannotReadAnotherDoctorScan() throws Exception {
            String patientCode = createPatient(doctorAToken);
            String publicId = uploadScan(doctorAToken, patientCode);

            mockMvc.perform(get("/api/v1/scans/" + publicId)
                            .header("Authorization", "Bearer " + doctorBToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SCAN_NOT_FOUND"));
        }

        @Test
        @DisplayName("admin can read any doctor's scan")
        void adminCanReadScan() throws Exception {
            String patientCode = createPatient(doctorAToken);
            String publicId = uploadScan(doctorAToken, patientCode);

            mockMvc.perform(get("/api/v1/scans/" + publicId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.publicId").value(publicId));
        }

        @Test
        @DisplayName("researcher cannot upload a scan (403 Forbidden)")
        void researcherCannotUpload() throws Exception {
            String patientCode = createPatient(doctorAToken);
            byte[] pngBytes = createSampleImage("png", 16, 16);
            MockMultipartFile file = new MockMultipartFile("file", "scan.png", "image/png", pngBytes);

            mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                            .file(file)
                            .param("scanDate", "2026-08-20")
                            .param("scanType", "MRI_T1")
                            .header("Authorization", "Bearer " + researcherToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("researcher can read patient scans")
        void researcherCanReadScan() throws Exception {
            String patientCode = createPatient(doctorAToken);
            String publicId = uploadScan(doctorAToken, patientCode);

            mockMvc.perform(get("/api/v1/scans/" + publicId)
                            .header("Authorization", "Bearer " + researcherToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.publicId").value(publicId));
        }
    }

    @Nested
    @DisplayName("image download and audit logging")
    class ImageDownloadAndAudit {

        @Test
        @DisplayName("image download endpoint returns exact original binary bytes")
        void imageDownloadReturnsExactBytes() throws Exception {
            String patientCode = createPatient(doctorAToken);
            byte[] pngBytes = createSampleImage("png", 20, 20);

            MockMultipartFile file = new MockMultipartFile("file", "download_test.png", "image/png", pngBytes);

            MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                            .file(file)
                            .param("scanDate", "2026-08-20")
                            .param("scanType", "MRI_T1")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isCreated())
                    .andReturn();

            String publicId = extractPublicId(uploadResult);

            MvcResult downloadResult = mockMvc.perform(get("/api/v1/scans/" + publicId + "/image")
                            .header("Authorization", "Bearer " + doctorAToken))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/png"))
                    .andReturn();

            assertThat(downloadResult.getResponse().getContentAsByteArray()).isEqualTo(pngBytes);
        }

        @Test
        @DisplayName("audit logs record successful scan upload")
        void auditLogRecordsUpload() throws Exception {
            String patientCode = createPatient(doctorAToken);
            String publicId = uploadScan(doctorAToken, patientCode);

            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM audit_logs
                     WHERE action = 'SCAN_UPLOADED' AND resource_id = ? AND success = TRUE
                    """, Integer.class, publicId);

            assertThat(count).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String createPatient(String token) throws Exception {
        String code = "BTX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientCode\":\"" + code + "\",\"birthYear\":1990}"))
                .andExpect(status().isCreated());
        return code;
    }

    private String uploadScan(String token, String patientCode) throws Exception {
        byte[] pngBytes = createSampleImage("png", 16, 16);
        MockMultipartFile file = new MockMultipartFile("file", "scan.png", "image/png", pngBytes);

        MvcResult result = mockMvc.perform(multipart("/api/v1/patients/" + patientCode + "/scans")
                        .file(file)
                        .param("scanDate", "2026-08-20")
                        .param("scanType", "MRI_T1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();

        return extractPublicId(result);
    }

    private String extractPublicId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        int idx = body.indexOf("\"publicId\":\"");
        return body.substring(idx + 12, body.indexOf('"', idx + 12));
    }

    private User createUser(Role role) {
        String username = role.name().toLowerCase() + "." + UUID.randomUUID().toString().substring(0, 8);
        User user = new User(username, username + "@example.invalid",
                passwordEncoder.encode("Password-Secret-1"), "Test " + role, role);
        return userRepository.save(user);
    }

    private String tokenFor(User user) {
        return jwtService.issueAccessToken(user, Instant.now());
    }
}
