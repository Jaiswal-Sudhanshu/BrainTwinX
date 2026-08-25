package com.braintwinx.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.braintwinx.AbstractIntegrationTest;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.User;
import com.braintwinx.repository.UserRepository;
import com.braintwinx.security.JwtService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end verification of patient management against real MySQL.
 *
 * <p>The assertions that matter most are the ones a happy-path suite would miss:
 * {@link #doctorCannotReadAnotherDoctorsPatient()} (IDOR), {@link #errorResponsesCarryNoPhi()}
 * (PHI leakage), and {@link #responseOmitsInternalIdAndCreator()} (over-exposure).
 */
@DisplayName("Patient management")
class PatientManagementIT extends AbstractIntegrationTest {

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
    private String doctorAUsername;

    @BeforeEach
    void setUp() {
        if (mockMvc == null) {
            // The correlation filter is added explicitly: MockMvc does not auto-register plain
            // Filter beans the way the servlet container does (see TROUBLESHOOTING T-10).
            mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                    .addFilters(correlationIdFilter)
                    .apply(org.springframework.security.test.web.servlet.setup
                            .SecurityMockMvcConfigurers.springSecurity())
                    .build();
        }
        // Fresh users per test so scope assertions cannot be contaminated by earlier state.
        adminToken = tokenFor(createUser(Role.ADMIN));
        User doctorA = createUser(Role.DOCTOR);
        doctorAUsername = doctorA.getUsername();
        doctorAToken = tokenFor(doctorA);
        doctorBToken = tokenFor(createUser(Role.DOCTOR));
        researcherToken = tokenFor(createUser(Role.RESEARCHER));
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a doctor can create a patient and gets a Location addressed by patientCode")
    void createReturns201WithLocation() throws Exception {
        String code = uniqueCode();

        mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + doctorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientCode":"%s","birthYear":1985,"sex":"FEMALE"}
                                """.formatted(code)))
                .andExpect(status().isCreated())
                // Addressed by the public-safe code, never an internal id.
                .andExpect(header().string("Location", "/api/v1/patients/" + code))
                .andExpect(jsonPath("$.patientCode").value(code))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
    }

    @Test
    @DisplayName("a duplicate patient code is rejected with 409")
    void duplicateCodeRejected() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);

        mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + doctorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientCode":"%s"}
                                """.formatted(code)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    @DisplayName("sex defaults to UNKNOWN rather than being guessed")
    void sexDefaultsToUnknown() throws Exception {
        // Data minimisation: an absent value is recorded as absent, never inferred.
        String code = uniqueCode();

        mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + doctorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientCode":"%s"}
                                """.formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sex").value("UNKNOWN"))
                .andExpect(jsonPath("$.birthYear").doesNotExist());
    }

    @Test
    @DisplayName("a researcher cannot create a patient")
    void researcherCannotCreate() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + researcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientCode":"%s"}
                                """.formatted(uniqueCode())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("creating without a token is rejected")
    void createRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientCode":"%s"}
                                """.formatted(uniqueCode())))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a blank patient code is rejected with a field-level error")
    void blankCodeRejected() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + doctorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientCode\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("patientCode"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../etc/passwd",          // path traversal characters
            "code with spaces",
            "code/slash",
            "code\\\\backslash",
            "-leading-hyphen",        // must start alphanumeric
            "code;semicolon",
            "code'quote"
    })
    @DisplayName("a malformed patient code is rejected before reaching the service")
    void malformedCodeRejected(String badCode) throws Exception {
        // The code reaches log lines and audit rows, so its shape is constrained at the boundary.
        MvcResult result = mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + doctorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectJson(badCode)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("INVALID_REQUEST");
    }

    @ParameterizedTest
    @CsvSource({"1899", "2201", "0", "-5"})
    @DisplayName("an out-of-range birth year is rejected")
    void outOfRangeBirthYearRejected(short year) throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + doctorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientCode":"%s","birthYear":%d}
                                """.formatted(uniqueCode(), year)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("birthYear"));
    }

    @Test
    @DisplayName("an unknown field in the body is rejected rather than silently ignored")
    void unknownFieldRejected() throws Exception {
        // fail-on-unknown-properties is enabled: a client sending patientName must be told the
        // field does not exist, not have it quietly dropped while believing it was stored.
        mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + doctorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientCode":"%s","patientName":"Jane Doe"}
                                """.formatted(uniqueCode())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("an oversized page size is rejected")
    void oversizedPageRejected() throws Exception {
        mockMvc.perform(get("/api/v1/patients")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("size", "5000"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // IDOR — the central security assertion of this phase
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a doctor cannot read another doctor's patient, and gets 404 not 403")
    void doctorCannotReadAnotherDoctorsPatient() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);

        MvcResult result = mockMvc.perform(get("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + doctorBToken))
                // 404, deliberately: a 403 would confirm the record exists, letting a caller
                // enumerate another clinician's caseload one code at a time.
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"))
                .andReturn();

        // The response must be byte-identical in shape to a genuinely absent record.
        MvcResult absent = mockMvc.perform(get("/api/v1/patients/" + uniqueCode())
                        .header("Authorization", "Bearer " + doctorBToken))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(codeOf(result)).isEqualTo(codeOf(absent));
    }

    @Test
    @DisplayName("a doctor cannot update another doctor's patient")
    void doctorCannotUpdateAnotherDoctorsPatient() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);

        mockMvc.perform(put("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + doctorBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthYear\":1990}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a doctor cannot archive another doctor's patient")
    void doctorCannotArchiveAnotherDoctorsPatient() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);

        mockMvc.perform(delete("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + doctorBToken))
                .andExpect(status().isNotFound());

        // And the record must genuinely still be active.
        mockMvc.perform(get("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + doctorAToken))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("a doctor's listing contains only their own patients")
    void listIsScopedForDoctor() throws Exception {
        String ownCode = uniqueCode();
        String otherCode = uniqueCode();
        createPatient(doctorAToken, ownCode);
        createPatient(doctorBToken, otherCode);

        MvcResult result = mockMvc.perform(get("/api/v1/patients")
                        .header("Authorization", "Bearer " + doctorAToken)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains(ownCode);
        assertThat(body)
                .as("Another clinician's patient must not appear in a scoped listing")
                .doesNotContain(otherCode);
    }

    @Test
    @DisplayName("an admin can read any patient")
    void adminHasUnrestrictedScope() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);

        mockMvc.perform(get("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientCode").value(code));
    }

    @Test
    @DisplayName("a researcher can read across cohorts but cannot modify")
    void researcherIsReadOnly() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);

        mockMvc.perform(get("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + researcherToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + researcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthYear\":1990}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + researcherToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // PHI leakage
    // ------------------------------------------------------------------

    @Test
    @DisplayName("error responses carry no PHI")
    void errorResponsesCarryNoPhi() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code, (short) 1972, "MALE");

        // A rejected access must not echo the record's contents back.
        MvcResult result = mockMvc.perform(get("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + doctorBToken))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("A denied response must not disclose the record it refused to show")
                .doesNotContain("1972")
                .doesNotContain("MALE")
                .doesNotContain("birthYear")
                .doesNotContain("sex");
    }

    @Test
    @DisplayName("a validation failure does not echo the rejected value")
    void validationErrorDoesNotEchoValue() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + doctorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientCode":"%s","birthYear":1899}
                                """.formatted(uniqueCode())))
                .andExpect(status().isBadRequest())
                .andReturn();

        // Field name and reason are returned because the caller cannot fix the request
        // otherwise; the rejected value itself is not.
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("birthYear");
        assertThat(body).doesNotContain("1899");
    }

    @Test
    @DisplayName("the response omits the internal id and the creating user")
    void responseOmitsInternalIdAndCreator() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);

        MvcResult result = mockMvc.perform(get("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + doctorAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.createdBy").doesNotExist())
                .andExpect(jsonPath("$.lockVersion").doesNotExist())
                .andReturn();

        // The creating clinician's identity must not leak to anyone who can read the patient.
        assertThat(result.getResponse().getContentAsString()).doesNotContain(doctorAUsername);
    }

    // ------------------------------------------------------------------
    // Update and archive
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an update leaves omitted fields unchanged")
    void updateIsPartial() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code, (short) 1980, "FEMALE");

        mockMvc.perform(put("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + doctorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthYear\":1981}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.birthYear").value(1981))
                // Omitted rather than nulled: silently erasing it would be data loss.
                .andExpect(jsonPath("$.sex").value("FEMALE"));
    }

    @Test
    @DisplayName("archiving is a soft operation and is idempotent")
    void archiveIsSoftAndIdempotent() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);

        mockMvc.perform(delete("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + doctorAToken))
                .andExpect(status().isNoContent());

        // Still retrievable — archive never destroys the record (ASSUMPTIONS.md A-13).
        mockMvc.perform(get("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + doctorAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.archivedAt").exists());

        // Second archive is a no-op, not an error.
        mockMvc.perform(delete("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + doctorAToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("an archived patient cannot be updated")
    void archivedPatientCannotBeUpdated() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);
        mockMvc.perform(delete("/api/v1/patients/" + code)
                .header("Authorization", "Bearer " + doctorAToken));

        mockMvc.perform(put("/api/v1/patients/" + code)
                        .header("Authorization", "Bearer " + doctorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthYear\":1990}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("archived patients are excluded from the default listing")
    void archivedExcludedFromDefaultListing() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);
        mockMvc.perform(delete("/api/v1/patients/" + code)
                .header("Authorization", "Bearer " + doctorAToken));

        MvcResult active = mockMvc.perform(get("/api/v1/patients")
                        .header("Authorization", "Bearer " + doctorAToken)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(active.getResponse().getContentAsString()).doesNotContain(code);

        MvcResult archived = mockMvc.perform(get("/api/v1/patients")
                        .header("Authorization", "Bearer " + doctorAToken)
                        .param("status", "ARCHIVED")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(archived.getResponse().getContentAsString()).contains(code);
    }

    // ------------------------------------------------------------------
    // Cross-cutting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every patient response carries a trace id")
    void traceIdPresent() throws Exception {
        mockMvc.perform(get("/api/v1/patients/" + uniqueCode())
                        .header("Authorization", "Bearer " + doctorAToken))
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("create, view, update and archive are all audited")
    void operationsAreAudited() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);
        mockMvc.perform(get("/api/v1/patients/" + code)
                .header("Authorization", "Bearer " + doctorAToken));
        mockMvc.perform(put("/api/v1/patients/" + code)
                .header("Authorization", "Bearer " + doctorAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"birthYear\":1990}"));
        mockMvc.perform(delete("/api/v1/patients/" + code)
                .header("Authorization", "Bearer " + doctorAToken));

        for (AuditAction action : new AuditAction[]{
                AuditAction.PATIENT_CREATED, AuditAction.PATIENT_VIEWED,
                AuditAction.PATIENT_UPDATED, AuditAction.PATIENT_ARCHIVED}) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM audit_logs
                     WHERE action = ? AND resource_id = ? AND success = TRUE
                    """, Integer.class, action.name(), code);
            assertThat(count).as("%s must be audited for %s", action, code).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("audit rows record the public code, never an internal id")
    void auditUsesPublicIdentifier() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);

        String resourceId = jdbcTemplate.queryForObject("""
                SELECT resource_id FROM audit_logs
                 WHERE action = 'PATIENT_CREATED' AND resource_id = ?
                """, String.class, code);

        assertThat(resourceId).isEqualTo(code).isNotEqualTo("1");
    }

    @Test
    @DisplayName("a failed create is audited as unsuccessful")
    void failedCreateIsAudited() throws Exception {
        String code = uniqueCode();
        createPatient(doctorAToken, code);
        // Duplicate attempt.
        mockMvc.perform(post("/api/v1/patients")
                .header("Authorization", "Bearer " + doctorAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectJson(code)));

        Integer failures = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                 WHERE action = 'PATIENT_CREATED' AND resource_id = ? AND success = FALSE
                """, Integer.class, code);

        assertThat(failures).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private String uniqueCode() {
        return "BTX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String objectJson(String patientCode) {
        // Manual construction so hostile codes are transmitted verbatim rather than escaped away.
        return "{\"patientCode\":\"" + patientCode + "\"}";
    }

    private User createUser(Role role) {
        String username = role.name().toLowerCase() + "." + UUID.randomUUID().toString()
                .substring(0, 8);
        User user = new User(username, username + "@example.invalid",
                passwordEncoder.encode("Correct-Horse-Battery-Staple-1"), "Test " + role, role);
        return userRepository.save(user);
    }

    private String tokenFor(User user) {
        return jwtService.issueAccessToken(user, Instant.now());
    }

    private void createPatient(String token, String code) throws Exception {
        createPatient(token, code, null, null);
    }

    private void createPatient(String token, String code, Short birthYear, String sex)
            throws Exception {
        StringBuilder json = new StringBuilder("{\"patientCode\":\"").append(code).append('"');
        if (birthYear != null) {
            json.append(",\"birthYear\":").append(birthYear);
        }
        if (sex != null) {
            json.append(",\"sex\":\"").append(sex).append('"');
        }
        json.append('}');

        mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.toString()))
                .andExpect(status().isCreated());
    }

    private String codeOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        int idx = body.indexOf("\"code\":\"");
        return body.substring(idx + 8, body.indexOf('"', idx + 8));
    }
}
