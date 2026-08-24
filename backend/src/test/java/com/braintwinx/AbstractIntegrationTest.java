package com.braintwinx;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Base class for integration tests, backed by a real MySQL container.
 *
 * <p>MySQL rather than H2 on purpose. The schema relies on MySQL CHECK constraints to enforce
 * medical-safety invariants — for example that an {@code INSUFFICIENT_HISTORY} growth analysis
 * cannot carry a forecast. An in-memory substitute would not enforce those constraints, so a suite
 * running on H2 could pass while the invariants it claims to verify were absent from the database
 * that actually matters.
 *
 * <p><strong>Singleton container, started manually.</strong> The container is <em>not</em> managed
 * by {@code @Testcontainers} / {@code @Container}. That combination ties the container's lifecycle
 * to the test class, and because this field is inherited, the extension stops the container after
 * the <em>first</em> subclass finishes — leaving every later class pointing at a dead database
 * ("Communications link failure"). Meanwhile Spring caches the context with the original JDBC URL,
 * so the failure looks like a connectivity problem rather than a lifecycle one.
 *
 * <p>Starting it once in a static initialiser and never stopping it gives a single container shared
 * by every integration test in the JVM. Cleanup is handled by the Testcontainers Ryuk sidecar at
 * JVM exit, so nothing is leaked.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    /**
     * MySQL 8.4 (LTS). Must be 8.0.16 or later: CHECK constraints are only enforced from that
     * version, and on an earlier one they parse but are silently ignored.
     */
    @SuppressWarnings("resource") // Deliberately never closed; Ryuk reaps it at JVM exit.
    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("braintwinx_test")
                    .withUsername("braintwinx_test")
                    .withPassword("braintwinx_test_pw")
                    // Reuse across classes in the same run; no per-class restart cost.
                    .withReuse(false);

    static {
        MYSQL.start();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        // A real 256-bit secret, generated for tests only and never used elsewhere.
        // application.yml deliberately provides no default for JWT_SECRET so a missing value
        // aborts startup; tests must therefore supply one explicitly. It must also survive
        // JwtProperties.validate(), which rejects short and placeholder-looking values — so this
        // cannot be a token like "test-secret".
        registry.add("braintwinx.jwt.secret",
                () -> "9e2c7a4f1b8d3e6a5c0f7b2d9e4a1c8f3b6d0e7a2c5f8b1d4e7a0c3f6b9d2e5a");
        registry.add("braintwinx.cors.allowed-origins", () -> "http://localhost:5173");
    }

    /**
     * Guards the premise of every CHECK-constraint assertion in this suite.
     *
     * <p>If the container image were ever downgraded below 8.0.16, the safety tests would pass
     * vacuously because the constraints would not be enforced. This fails loudly instead.
     */
    @Test
    void mysqlVersionEnforcesCheckConstraints() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        org.assertj.core.api.Assertions.assertThat(version).isNotNull();

        String[] parts = version.split("[.-]");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = Integer.parseInt(parts[2]);

        boolean enforcesChecks = major > 8
                || (major == 8 && minor > 0)
                || (major == 8 && minor == 0 && patch >= 16);

        org.assertj.core.api.Assertions
                .assertThat(enforcesChecks)
                .as("MySQL %s must be >= 8.0.16 or CHECK constraints are silently ignored", version)
                .isTrue();
    }
}
