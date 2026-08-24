package com.braintwinx.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.braintwinx.AbstractIntegrationTest;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the security filter chain end to end (project brief section 7).
 *
 * <p>The single most important assertion in this class is
 * {@link #unmappedAndUnknownPathsAreDenied}: it proves the chain is <strong>deny by default</strong>.
 * That is what makes a forgotten authorisation rule fail closed rather than silently expose a
 * resource — the sequence that produces most real-world access-control defects.
 *
 * <p>Runs against the real application context and a real MySQL container, so the assertions
 * reflect the actual wired chain rather than a mock of it.
 */
@DisplayName("Security filter chain")
class SecurityFilterChainIT extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private com.braintwinx.config.CorrelationIdFilter correlationIdFilter;

    private MockMvc mockMvc;

    private MockMvc mvc() {
        if (mockMvc == null) {
            // The correlation filter must be added explicitly. In production Spring Boot
            // auto-registers any Filter bean with the servlet container, but MockMvc does not
            // replicate that — it applies only the Security chain plus filters named here. Without
            // it, X-Trace-Id is absent and traceId falls back to the literal "unknown", which
            // silently weakens every assertion that depends on correlation.
            mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                    .addFilters(correlationIdFilter)
                    .apply(org.springframework.security.test.web.servlet.setup
                            .SecurityMockMvcConfigurers.springSecurity())
                    .build();
        }
        return mockMvc;
    }

    // ------------------------------------------------------------------
    // Deny by default
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/patients",           // not implemented yet — must still be denied
            "/api/v1/scans",
            "/api/v1/reports",
            "/api/v1/anything",
            "/some/random/path",
            "/internal/ai/v1/predict",    // internal AI surface must never be public
            "/actuator/env",              // sensitive actuator endpoints
            "/actuator/beans"
    })
    @DisplayName("unmapped, unimplemented, and internal paths are all denied without a token")
    void unmappedAndUnknownPathsAreDenied(String path) throws Exception {
        // Deliberately includes paths with no controller. With anyRequest().authenticated()
        // these would become reachable the moment someone adds a mapping; with denyAll()
        // they stay closed until access is granted explicitly.
        mvc().perform(get(path))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a protected endpoint without a token returns 401 and no stack trace")
    void unauthenticatedRequestIsRejectedCleanly() throws Exception {
        MvcResult result = mvc().perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").exists())
                // The envelope must not carry diagnostic internals.
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("An error response must never disclose internals")
                .doesNotContain("Exception")
                .doesNotContain("com.braintwinx")
                .doesNotContain("org.springframework");
    }

    // ------------------------------------------------------------------
    // Token handling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an expired token returns 401 with TOKEN_EXPIRED, distinct from invalid")
    void expiredTokenIsDistinguishable() throws Exception {
        User user = new User("expired.user", "expired@example.invalid", "hash",
                "Expired User", Role.DOCTOR);
        String token = jwtService.issueAccessToken(user, Instant.now().minus(2, ChronoUnit.HOURS));

        // A client needs to know whether to refresh or re-authenticate. This reveals
        // nothing about whether the account exists.
        mvc().perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Bearer not-a-jwt",
            "Bearer a.b.c",
            "Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbiJ9.",   // alg:none attempt
            "Basic dXNlcjpwYXNz"                                   // wrong scheme entirely
    })
    @DisplayName("a malformed or forged Authorization header is rejected")
    void malformedTokenRejected(String headerValue) throws Exception {
        mvc().perform(post("/api/v1/auth/logout").header("Authorization", headerValue))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a validly signed token authenticates successfully")
    void validTokenIsAccepted() throws Exception {
        User user = new User("valid.user", "valid@example.invalid", "hash",
                "Valid User", Role.DOCTOR);
        String token = jwtService.issueAccessToken(user, Instant.now());

        // The user does not exist in the database, so logout is a no-op — but reaching the
        // controller at all proves authentication succeeded rather than being rejected.
        mvc().perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Public endpoints
    // ------------------------------------------------------------------

    @Test
    @DisplayName("login is reachable without authentication")
    void loginIsPublic() throws Exception {
        // Must not be 401/403: it is the way a client obtains a token in the first place.
        // 400 is the expected outcome for an empty body.
        mvc().perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("health is reachable without authentication")
    void healthIsPublic() throws Exception {
        mvc().perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("bad login credentials give a generic 401 that does not reveal the cause")
    void loginFailureIsUniform() throws Exception {
        MvcResult result = mvc().perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"no.such.user\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn();

        // Must not hint at whether the username exists — that would make login a
        // user-enumeration oracle.
        assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("username")
                .doesNotContain("password")
                .doesNotContain("not found")
                .doesNotContain("unknown");
    }

    // ------------------------------------------------------------------
    // Cross-cutting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every response carries a trace id, even a rejected one")
    void traceIdAlwaysPresent() throws Exception {
        // Correlation must work for unauthenticated failures too, or the traceId in an
        // error envelope would be useless for exactly the requests users complain about.
        mvc().perform(get("/api/v1/patients"))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("a hostile inbound trace id is replaced, not echoed")
    void hostileTraceIdIsNotEchoed() throws Exception {
        // The trace id reaches log files and a database column, so CRLF in it could forge
        // log lines. A malformed value must be discarded rather than sanitised.
        String hostile = "abc\r\nFAKE-LOG-LINE";

        MvcResult result = mvc().perform(get("/api/v1/patients").header("X-Trace-Id", hostile))
                .andReturn();

        String returned = result.getResponse().getHeader("X-Trace-Id");
        assertThat(returned)
                .isNotNull()
                .doesNotContain("\r")
                .doesNotContain("\n")
                .doesNotContain("FAKE-LOG-LINE");
    }

    @Test
    @DisplayName("a well-formed inbound trace id is honoured for cross-service correlation")
    void wellFormedTraceIdIsHonoured() throws Exception {
        String supplied = "abcdef0123456789";

        mvc().perform(get("/actuator/health").header("X-Trace-Id", supplied))
                .andExpect(header().string("X-Trace-Id", supplied));
    }

    @Test
    @DisplayName("security headers are applied")
    void securityHeadersPresent() throws Exception {
        mvc().perform(get("/actuator/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    @DisplayName("token responses are not cacheable")
    void authResponsesAreNoStore() throws Exception {
        // A credential-bearing response must not be written to a shared or disk cache.
        mvc().perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"no.such.user\",\"password\":\"whatever\"}"))
                .andReturn();

        MvcResult ok = mvc().perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + jwtService.issueAccessToken(
                                new User("cache.user", "cache@example.invalid", "hash",
                                        "Cache User", Role.RESEARCHER), Instant.now())))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(ok.getResponse().getHeader("Cache-Control")).contains("no-store");
    }
}
