package com.braintwinx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Verifies the V1 baseline schema against a real MySQL instance.
 *
 * <p>Two distinct things are proven here.
 *
 * <ol>
 *   <li><strong>The migration applies and matches the entity model.</strong> The Spring
 *       context runs with {@code hibernate.ddl-auto=validate}, so the fact that these tests
 *       start at all proves every JPA mapping agrees with the migrated schema. A column
 *       rename in either place breaks the build rather than surfacing at runtime.</li>
 *   <li><strong>The medical-safety invariants are actually enforced by the database</strong>
 *       — not merely documented, and not merely checked in Java where a future code path
 *       could bypass them. Each is exercised with raw SQL that deliberately attempts the
 *       forbidden write.</li>
 * </ol>
 */
@DisplayName("V1 baseline schema")
class SchemaMigrationIT extends AbstractIntegrationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "analysis_jobs",
            "audit_logs",
            "growth_predictions",
            "model_versions",
            "patients",
            "predictions",
            "refresh_tokens",
            "reports",
            "scans",
            "segmentation_results",
            "users");

    @Test
    @DisplayName("Flyway records the baseline migration as applied and successful")
    void flywayAppliedBaseline() {
        Integer successCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version = '1' AND success = 1
                """, Integer.class);

        assertThat(successCount)
                .as("V1 baseline migration must be recorded as applied")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("every expected table exists")
    void allTablesCreated() {
        List<String> actual = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                 ORDER BY table_name
                """, String.class);

        assertThat(actual).containsExactlyElementsOf(EXPECTED_TABLES);
    }

    @Test
    @DisplayName("segmentation_results has no dice or iou column")
    void segmentationHasNoEvaluationMetricColumns() {
        // Dice and IoU require a ground-truth mask, which does not exist for a production
        // scan. Their absence is the structural guarantee that no code path can attach a
        // fabricated accuracy figure to a single inference (brief section 12).
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'segmentation_results'
                """, String.class);

        assertThat(columns)
                .as("Dice/IoU are evaluation-only metrics and must not be storable per inference")
                .noneMatch(c -> c.toLowerCase().contains("dice") || c.toLowerCase().contains("iou"));
    }

    @Test
    @DisplayName("every inference table carries is_synthetic")
    void inferenceTablesTrackSyntheticOutput() {
        // Makes development-stub output structurally distinguishable from real model
        // output, so it can never be silently presented as clinical (ASSUMPTIONS.md A-2).
        for (String table : List.of("predictions", "segmentation_results", "growth_predictions")) {
            Integer present = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = ?
                       AND column_name = 'is_synthetic'
                    """, Integer.class, table);

            assertThat(present).as("%s must carry is_synthetic", table).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("INSUFFICIENT_HISTORY cannot be stored alongside a forecast")
    void databaseRejectsForecastWithInsufficientHistory() {
        // The core invariant of brief section 13, enforced in the database so that even a
        // future code path that tried to fabricate a trend would be rejected.
        long patientId = insertPatientWithUser();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO growth_predictions
                    (public_id, patient_id, status, observation_count,
                     trend_direction, forecast, is_synthetic, created_at)
                VALUES (UUID(), ?, 'INSUFFICIENT_HISTORY', 1,
                        'INCREASING', '{"points":[1,2,3]}', FALSE, NOW(6))
                """, patientId))
                .as("A forecast must not be storable next to an insufficient-history outcome")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("INSUFFICIENT_HISTORY is storable when it carries no forecast")
    void databaseAcceptsHonestInsufficientHistory() {
        // The complement of the previous test: recording the honest outcome must work, or
        // the constraint would have made the correct behaviour impossible too.
        long patientId = insertPatientWithUser();

        int inserted = jdbcTemplate.update("""
                INSERT INTO growth_predictions
                    (public_id, patient_id, status, observation_count, is_synthetic, created_at)
                VALUES (UUID(), ?, 'INSUFFICIENT_HISTORY', 1, FALSE, NOW(6))
                """, patientId);

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("a COMPLETED trend estimate must be attributable to a model version")
    void databaseRejectsUnattributedCompletedForecast() {
        long patientId = insertPatientWithUser();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO growth_predictions
                    (public_id, patient_id, status, observation_count,
                     trend_direction, is_synthetic, created_at)
                VALUES (UUID(), ?, 'COMPLETED', 5, 'STABLE', FALSE, NOW(6))
                """, patientId))
                .as("A completed estimate without a model version must be rejected")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("prediction confidence is constrained to [0,1]")
    void databaseRejectsOutOfRangeConfidence() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO predictions
                    (public_id, scan_id, predicted_class, confidence, probabilities,
                     model_version_id, preprocessing_version, inference_timestamp,
                     is_synthetic, created_at)
                VALUES (UUID(), 1, 'glioma', 1.5, '{}', 1, '1.0.0', NOW(6), FALSE, NOW(6))
                """))
                .as("A confidence above 1 must never be storable")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a FAILED scan must record why it failed")
    void databaseRejectsFailedScanWithoutReason() {
        long patientId = insertPatientWithUser();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO scans
                    (public_id, patient_id, scan_date, scan_type, storage_key,
                     detected_mime_type, file_size_bytes, content_sha256, status,
                     uploaded_by_user_id, created_at, updated_at)
                VALUES (UUID(), ?, '2026-01-01', 'MRI_T1', 'scans/no-reason.png',
                        'image/png', 1024, REPEAT('a', 64), 'FAILED',
                        1, NOW(6), NOW(6))
                """, patientId))
                .as("Silent failure must not be representable")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an archived patient must carry an archive timestamp")
    void databaseRejectsInconsistentArchiveState() {
        insertUserIfAbsent();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO patients
                    (patient_code, sex, status, created_by_user_id, created_at, updated_at)
                VALUES ('BTX-INCONSISTENT', 'UNKNOWN', 'ARCHIVED', 1, NOW(6), NOW(6))
                """))
                .as("Archive status and archive timestamp must agree")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("duplicate content for the same patient is rejected")
    void databaseRejectsDuplicateUploadForSamePatient() {
        long patientId = insertPatientWithUser();
        String sha = "b".repeat(64);

        jdbcTemplate.update("""
                INSERT INTO scans
                    (public_id, patient_id, scan_date, scan_type, storage_key,
                     detected_mime_type, file_size_bytes, content_sha256, status,
                     uploaded_by_user_id, created_at, updated_at)
                VALUES (UUID(), ?, '2026-01-01', 'MRI_T1', ?, 'image/png', 2048, ?,
                        'UPLOADED', 1, NOW(6), NOW(6))
                """, patientId, "scans/dup-" + patientId + "-1.png", sha);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO scans
                    (public_id, patient_id, scan_date, scan_type, storage_key,
                     detected_mime_type, file_size_bytes, content_sha256, status,
                     uploaded_by_user_id, created_at, updated_at)
                VALUES (UUID(), ?, '2026-02-01', 'MRI_T1', ?, 'image/png', 2048, ?,
                        'UPLOADED', 1, NOW(6), NOW(6))
                """, patientId, "scans/dup-" + patientId + "-2.png", sha))
                .as("The same file must not be uploadable twice for one patient")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // Fixtures. Raw SQL is used deliberately: these tests verify the schema
    // itself, so going through JPA would test the mapping instead.
    // ------------------------------------------------------------------

    private void insertUserIfAbsent() {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = 1", Integer.class);
        if (existing != null && existing > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO users
                    (id, public_id, username, email, password_hash, full_name, role,
                     enabled, failed_login_attempts, created_at, updated_at, version)
                VALUES (1, UUID(), 'schema_fixture', 'fixture@example.invalid',
                        'not-a-real-hash', 'Schema Fixture', 'ADMIN',
                        TRUE, 0, NOW(6), NOW(6), 0)
                """);
    }

    /** @return the id of a freshly created active patient */
    private long insertPatientWithUser() {
        insertUserIfAbsent();
        String code = "BTX-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO patients
                    (patient_code, sex, status, created_by_user_id, created_at, updated_at)
                VALUES (?, 'UNKNOWN', 'ACTIVE', 1, NOW(6), NOW(6))
                """, code);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM patients WHERE patient_code = ?", Long.class, code);
        assertThat(id).isNotNull();
        return id;
    }
}
