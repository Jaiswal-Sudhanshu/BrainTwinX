package com.braintwinx;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests, backed by a real MySQL container.
 *
 * <p>MySQL rather than H2 on purpose. The schema relies on MySQL CHECK constraints to
 * enforce medical-safety invariants — for example that an {@code INSUFFICIENT_HISTORY}
 * growth analysis cannot carry a forecast. An in-memory substitute would not enforce those
 * constraints, so a test suite running on H2 could pass while the invariants it claims to
 * verify were absent in the database that actually matters.
 *
 * <p>The container is static so it is created once and shared across every integration
 * test, rather than paying container startup per class.
 *
 * <p>Datasource properties are supplied via {@link DynamicPropertySource}, which takes
 * precedence over {@code application.yml}. The environment variables that file expects are
 * therefore not required to run tests.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    /**
     * MySQL 8.4 (LTS). Must be 8.0.16 or later: CHECK constraints are only enforced from
     * that version, and on an earlier one they parse but are silently ignored.
     */
    @Container
    @SuppressWarnings("resource") // Lifecycle is managed by the Testcontainers extension.
    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("braintwinx_test")
                    .withUsername("braintwinx_test")
                    .withPassword("braintwinx_test_pw");

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /**
     * Guards the premise of every CHECK-constraint assertion in this suite.
     *
     * <p>If the container image were ever downgraded below 8.0.16, the safety tests would
     * pass vacuously because the constraints would not be enforced. This fails loudly
     * instead.
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
                .as("MySQL %s must be >= 8.0.16 or CHECK constraints are silently ignored",
                        version)
                .isTrue();
    }
}
