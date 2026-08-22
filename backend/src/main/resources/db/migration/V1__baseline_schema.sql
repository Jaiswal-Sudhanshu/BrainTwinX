-- ===========================================================================
-- BrainTwinX — V1 baseline schema
-- ===========================================================================
-- Target: MySQL 8.0.16+  (CHECK constraints are enforced from 8.0.16; on
--         earlier versions they parse but are ignored, which would silently
--         weaken the medical-safety invariants below.)
--
-- Conventions
--   * Surrogate BIGINT primary keys, signed, for clean Hibernate mapping.
--   * Every externally addressable row also carries an opaque `public_id`
--     (UUID). Sequential internal ids are never exposed in APIs or URLs
--     (brief section 8 / section 26).
--   * Enumerations are VARCHAR + CHECK rather than MySQL ENUM: portable,
--     and maps cleanly to JPA @Enumerated(EnumType.STRING) under
--     hibernate ddl-auto=validate.
--   * DATETIME(6) in UTC. The application is responsible for supplying UTC;
--     no server-timezone dependency is introduced.
--   * Timestamps are explicit columns, not triggers, so behaviour is visible
--     in the application layer.
--
-- Medical-safety invariants enforced *at the schema level* (not merely in
-- application code, so they cannot be bypassed by a future code path):
--
--   1. `is_synthetic` on every inference table. Development-stub output is
--      structurally distinguishable from real model output and can never be
--      silently mistaken for it. (ASSUMPTIONS.md A-2, A-15)
--   2. NO dice/iou columns on segmentation_results. Those are evaluation
--      metrics requiring ground truth; attaching one to a production
--      inference would be a fabricated measurement. (brief section 12)
--   3. growth_predictions CHECK: when status = 'INSUFFICIENT_HISTORY', the
--      forecast and trend columns MUST be NULL. It is physically impossible
--      to store a fabricated trend alongside an admission of insufficient
--      data. (brief section 13)
--   4. Confidence is constrained to [0,1] and cannot be NULL when a
--      prediction row exists — a prediction without a real confidence is not
--      representable.
--   5. refresh_tokens stores only a SHA-256 hash. A database disclosure does
--      not yield usable tokens.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    public_id              VARCHAR(36)  NOT NULL,
    username               VARCHAR(64)  NOT NULL,
    email                  VARCHAR(255) NOT NULL,
    -- BCrypt output is 60 chars; 100 leaves room for an algorithm change
    -- without a migration. Never stores plaintext.
    password_hash          VARCHAR(100) NOT NULL,
    full_name              VARCHAR(128) NOT NULL,
    role                   VARCHAR(16)  NOT NULL,
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Brute-force mitigation state (brief section 25 rate limiting).
    failed_login_attempts  INT          NOT NULL DEFAULT 0,
    locked_until           DATETIME(6)  NULL,
    last_login_at          DATETIME(6)  NULL,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_public_id UNIQUE (public_id),
    CONSTRAINT uq_users_username  UNIQUE (username),
    CONSTRAINT uq_users_email     UNIQUE (email),
    CONSTRAINT ck_users_role
        CHECK (role IN ('ADMIN', 'DOCTOR', 'RESEARCHER')),
    CONSTRAINT ck_users_failed_attempts
        CHECK (failed_login_attempts >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------------
-- refresh_tokens
-- ---------------------------------------------------------------------------
-- Rotating refresh tokens. Only the hash is persisted.
CREATE TABLE refresh_tokens (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    issued_at   DATETIME(6) NOT NULL,
    expires_at  DATETIME(6) NOT NULL,
    revoked_at  DATETIME(6) NULL,
    -- Set when this token is rotated, giving a replay-detection chain.
    replaced_by BIGINT      NULL,

    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_replaced_by
        FOREIGN KEY (replaced_by) REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > issued_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Query pattern: look up active tokens for a user; purge expired tokens.
CREATE INDEX ix_refresh_tokens_user       ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_expires_at ON refresh_tokens (expires_at);


-- ---------------------------------------------------------------------------
-- patients
-- ---------------------------------------------------------------------------
-- Data-minimised by design (brief section 26, ASSUMPTIONS.md A-8):
-- no name, no contact details, no address, no free-text history.
-- `birth_year` rather than full date of birth; sufficient for age context
-- without storing a direct identifier.
CREATE TABLE patients (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    -- Public-safe, non-sequential identifier used in all APIs and URLs.
    patient_code       VARCHAR(32) NOT NULL,
    birth_year         SMALLINT    NULL,
    sex                VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    -- Free-text clinical notes are deliberately NOT stored here.
    created_by_user_id BIGINT      NOT NULL,
    archived_at        DATETIME(6) NULL,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_patients PRIMARY KEY (id),
    CONSTRAINT uq_patients_code UNIQUE (patient_code),
    CONSTRAINT fk_patients_created_by
        FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_patients_sex
        CHECK (sex IN ('MALE', 'FEMALE', 'OTHER', 'UNKNOWN')),
    CONSTRAINT ck_patients_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_patients_birth_year
        CHECK (birth_year IS NULL OR (birth_year BETWEEN 1900 AND 2200)),
    -- Archive is a soft delete (ASSUMPTIONS.md A-13); the timestamp must
    -- agree with the status rather than drift from it.
    CONSTRAINT ck_patients_archived_consistency
        CHECK ((status = 'ARCHIVED' AND archived_at IS NOT NULL)
            OR (status = 'ACTIVE'   AND archived_at IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Query pattern: default listing excludes archived patients, newest first.
CREATE INDEX ix_patients_status_created ON patients (status, created_at);


-- ---------------------------------------------------------------------------
-- model_versions  (model registry — brief section 53)
-- ---------------------------------------------------------------------------
-- Every inference references the exact model row that produced it, making
-- results reproducible (brief section 22).
CREATE TABLE model_versions (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    model_name            VARCHAR(128) NOT NULL,
    model_type            VARCHAR(16)  NOT NULL,
    version               VARCHAR(32)  NOT NULL,
    framework             VARCHAR(64)  NOT NULL,
    -- SHA-256 of the weights file. When present, a mismatch at load time
    -- causes a refusal to load rather than a silent substitution.
    checksum_sha256       VARCHAR(64)  NULL,
    input_shape           VARCHAR(64)  NULL,
    -- The preprocessing contract this model was trained against. Changing
    -- preprocessing without a new model version is a correctness bug
    -- (brief section 10).
    preprocessing_version VARCHAR(32)  NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'INACTIVE',
    notes                 VARCHAR(512) NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    -- Optimistic locking: model status changes (activate / deprecate) are
    -- administrative operations that must not race.
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_model_versions PRIMARY KEY (id),
    CONSTRAINT uq_model_versions_name_version UNIQUE (model_name, version),
    CONSTRAINT ck_model_versions_type
        CHECK (model_type IN ('CLASSIFIER', 'SEGMENTER', 'FORECASTER')),
    CONSTRAINT ck_model_versions_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'DEPRECATED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_model_versions_type_status ON model_versions (model_type, status);


-- ---------------------------------------------------------------------------
-- scans
-- ---------------------------------------------------------------------------
CREATE TABLE scans (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    public_id          VARCHAR(36)  NOT NULL,
    patient_id         BIGINT       NOT NULL,
    scan_date          DATE         NOT NULL,
    scan_type          VARCHAR(16)  NOT NULL,

    -- Server-generated storage key. NEVER derived from client input
    -- (path-traversal defence, brief section 9).
    storage_key        VARCHAR(512) NOT NULL,
    -- Retained for display only. Must never be used to build a filesystem
    -- path.
    original_filename  VARCHAR(255) NULL,
    -- Content type as DETECTED by the server from magic bytes, not as
    -- declared by the client.
    detected_mime_type VARCHAR(100) NOT NULL,
    file_size_bytes    BIGINT       NOT NULL,
    content_sha256     VARCHAR(64)  NOT NULL,
    image_width        INT          NULL,
    image_height       INT          NULL,

    status             VARCHAR(16)  NOT NULL DEFAULT 'UPLOADED',
    failure_code       VARCHAR(64)  NULL,
    failure_reason     VARCHAR(512) NULL,

    uploaded_by_user_id BIGINT      NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_scans PRIMARY KEY (id),
    CONSTRAINT uq_scans_public_id   UNIQUE (public_id),
    CONSTRAINT uq_scans_storage_key UNIQUE (storage_key),
    -- The same image file cannot be uploaded twice for the same patient.
    -- Deliberately scoped per patient: an identical phantom/calibration
    -- image legitimately recurring across different patients is allowed.
    CONSTRAINT uq_scans_patient_content UNIQUE (patient_id, content_sha256),
    CONSTRAINT fk_scans_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id) ON DELETE RESTRICT,
    CONSTRAINT fk_scans_uploaded_by
        FOREIGN KEY (uploaded_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_scans_type
        CHECK (scan_type IN ('MRI_T1', 'MRI_T1C', 'MRI_T2', 'MRI_FLAIR', 'OTHER')),
    -- Lifecycle per brief section 52. Transition legality is enforced in the
    -- application state machine; this constraint bounds the value space.
    CONSTRAINT ck_scans_status
        CHECK (status IN ('UPLOADED', 'VALIDATING', 'VALIDATED',
                          'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    -- A failed scan must record why. Silent failure is not representable.
    CONSTRAINT ck_scans_failure_consistency
        CHECK ((status = 'FAILED' AND failure_code IS NOT NULL)
            OR (status <> 'FAILED' AND failure_code IS NULL)),
    CONSTRAINT ck_scans_file_size  CHECK (file_size_bytes > 0),
    CONSTRAINT ck_scans_dimensions
        CHECK ((image_width IS NULL AND image_height IS NULL)
            OR (image_width > 0 AND image_height > 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Query pattern: patient timeline, ordered by scan date (longitudinal analysis).
CREATE INDEX ix_scans_patient_date ON scans (patient_id, scan_date);
-- Query pattern: dashboard counts of pending/processing work.
CREATE INDEX ix_scans_status_created ON scans (status, created_at);


-- ---------------------------------------------------------------------------
-- analysis_jobs
-- ---------------------------------------------------------------------------
-- Not in the brief's minimum entity list, but required to satisfy
-- section 20 (asynchronous analysis) and section 21 (idempotency) without
-- introducing external infrastructure (ASSUMPTIONS.md A-9). Job state lives
-- in MySQL so it survives a restart and needs no broker.
CREATE TABLE analysis_jobs (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    public_id            VARCHAR(36)  NOT NULL,
    scan_id              BIGINT       NOT NULL,
    -- Idempotency: a repeated analyse request carrying the same key resolves
    -- to the existing job instead of creating duplicate work.
    idempotency_key      VARCHAR(128) NOT NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    progress_percent     SMALLINT     NOT NULL DEFAULT 0,
    attempt_count        INT          NOT NULL DEFAULT 0,
    error_code           VARCHAR(64)  NULL,
    error_message        VARCHAR(512) NULL,
    requested_by_user_id BIGINT       NOT NULL,
    queued_at            DATETIME(6)  NOT NULL,
    started_at           DATETIME(6)  NULL,
    finished_at          DATETIME(6)  NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_analysis_jobs PRIMARY KEY (id),
    CONSTRAINT uq_analysis_jobs_public_id UNIQUE (public_id),
    CONSTRAINT uq_analysis_jobs_idempotency UNIQUE (scan_id, idempotency_key),
    CONSTRAINT fk_analysis_jobs_scan
        FOREIGN KEY (scan_id) REFERENCES scans (id) ON DELETE CASCADE,
    CONSTRAINT fk_analysis_jobs_requested_by
        FOREIGN KEY (requested_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_analysis_jobs_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_analysis_jobs_progress
        CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_analysis_jobs_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_analysis_jobs_failure_consistency
        CHECK ((status = 'FAILED' AND error_code IS NOT NULL)
            OR (status <> 'FAILED' AND error_code IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Query pattern: worker picks up the oldest queued job.
CREATE INDEX ix_analysis_jobs_status_queued ON analysis_jobs (status, queued_at);
CREATE INDEX ix_analysis_jobs_scan          ON analysis_jobs (scan_id);


-- ---------------------------------------------------------------------------
-- predictions  (CNN classification output)
-- ---------------------------------------------------------------------------
-- Append-only history: re-analysis adds a row rather than overwriting, so a
-- previously issued report remains explainable by the record that produced it.
CREATE TABLE predictions (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    public_id             VARCHAR(36)  NOT NULL,
    scan_id               BIGINT       NOT NULL,
    analysis_job_id       BIGINT       NULL,

    predicted_class       VARCHAR(64)  NOT NULL,
    -- Real model output only. NOT NULL and range-checked: a prediction
    -- without a genuine confidence is not representable.
    confidence            DECIMAL(6,5) NOT NULL,
    -- Full probability distribution over all classes, as returned by the
    -- model. Stored so a result can be re-examined, not just its argmax.
    probabilities         JSON         NOT NULL,

    model_version_id      BIGINT       NOT NULL,
    preprocessing_version VARCHAR(32)  NOT NULL,
    inference_timestamp   DATETIME(6)  NOT NULL,
    inference_duration_ms INT          NULL,

    -- SAFETY: TRUE only for development-stub output. Allows any consumer —
    -- API, report, UI — to refuse to present it as clinical. Defaults to
    -- FALSE so a new code path cannot accidentally mark real output synthetic
    -- or vice versa without being explicit.
    is_synthetic          BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at            DATETIME(6)  NOT NULL,

    CONSTRAINT pk_predictions PRIMARY KEY (id),
    CONSTRAINT uq_predictions_public_id UNIQUE (public_id),
    CONSTRAINT fk_predictions_scan
        FOREIGN KEY (scan_id) REFERENCES scans (id) ON DELETE CASCADE,
    CONSTRAINT fk_predictions_job
        FOREIGN KEY (analysis_job_id) REFERENCES analysis_jobs (id) ON DELETE SET NULL,
    CONSTRAINT fk_predictions_model_version
        FOREIGN KEY (model_version_id) REFERENCES model_versions (id) ON DELETE RESTRICT,
    CONSTRAINT ck_predictions_confidence
        CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_predictions_duration
        CHECK (inference_duration_ms IS NULL OR inference_duration_ms >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Query pattern: latest prediction for a scan.
CREATE INDEX ix_predictions_scan_created ON predictions (scan_id, created_at);
CREATE INDEX ix_predictions_model_version ON predictions (model_version_id);


-- ---------------------------------------------------------------------------
-- segmentation_results  (U-Net output)
-- ---------------------------------------------------------------------------
-- NOTE ON ABSENT COLUMNS: there are deliberately no `dice_score` or
-- `iou_score` columns. Dice and IoU require a ground-truth mask, which does
-- not exist for a production scan. Storing them here would invite reporting a
-- fabricated accuracy figure per inference (brief section 12). Those metrics
-- belong exclusively to the offline evaluation harness.
CREATE TABLE segmentation_results (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    public_id             VARCHAR(36)  NOT NULL,
    scan_id               BIGINT       NOT NULL,
    prediction_id         BIGINT       NULL,
    analysis_job_id       BIGINT       NULL,

    tumor_detected        BOOLEAN      NOT NULL,
    -- Mask stored as a file artefact, not a database blob (brief section 4).
    mask_storage_key      VARCHAR(512) NULL,
    -- Units are in the column name on purpose: pixels of the PREPROCESSED
    -- image. Physical area (mm^2) needs pixel spacing that 2-D PNG/JPEG
    -- inputs do not carry (ASSUMPTIONS.md A-6, A-7).
    tumor_area_px         BIGINT       NULL,
    mask_width            INT          NULL,
    mask_height           INT          NULL,
    bbox_x                INT          NULL,
    bbox_y                INT          NULL,
    bbox_width            INT          NULL,
    bbox_height           INT          NULL,

    model_version_id      BIGINT       NOT NULL,
    preprocessing_version VARCHAR(32)  NOT NULL,
    inference_timestamp   DATETIME(6)  NOT NULL,
    inference_duration_ms INT          NULL,
    is_synthetic          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            DATETIME(6)  NOT NULL,

    CONSTRAINT pk_segmentation_results PRIMARY KEY (id),
    CONSTRAINT uq_segmentation_results_public_id UNIQUE (public_id),
    CONSTRAINT fk_segmentation_scan
        FOREIGN KEY (scan_id) REFERENCES scans (id) ON DELETE CASCADE,
    CONSTRAINT fk_segmentation_prediction
        FOREIGN KEY (prediction_id) REFERENCES predictions (id) ON DELETE SET NULL,
    CONSTRAINT fk_segmentation_job
        FOREIGN KEY (analysis_job_id) REFERENCES analysis_jobs (id) ON DELETE SET NULL,
    CONSTRAINT fk_segmentation_model_version
        FOREIGN KEY (model_version_id) REFERENCES model_versions (id) ON DELETE RESTRICT,
    -- No tumour detected means no mask and no area. Prevents a
    -- "not detected but here is its size" contradiction.
    CONSTRAINT ck_segmentation_detection_consistency
        CHECK ((tumor_detected = TRUE)
            OR (tumor_detected = FALSE AND tumor_area_px IS NULL
                                       AND bbox_x IS NULL)),
    CONSTRAINT ck_segmentation_area
        CHECK (tumor_area_px IS NULL OR tumor_area_px >= 0),
    CONSTRAINT ck_segmentation_mask_dims
        CHECK ((mask_width IS NULL AND mask_height IS NULL)
            OR (mask_width > 0 AND mask_height > 0)),
    -- A bounding box is all-or-nothing.
    CONSTRAINT ck_segmentation_bbox_complete
        CHECK ((bbox_x IS NULL AND bbox_y IS NULL
                AND bbox_width IS NULL AND bbox_height IS NULL)
            OR (bbox_x IS NOT NULL AND bbox_y IS NOT NULL
                AND bbox_width > 0 AND bbox_height > 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_segmentation_scan_created ON segmentation_results (scan_id, created_at);


-- ---------------------------------------------------------------------------
-- growth_predictions  (LSTM longitudinal trend estimate)
-- ---------------------------------------------------------------------------
-- The central invariant: an insufficient-history outcome is a first-class
-- recorded result, and the CHECK below makes it IMPOSSIBLE to store a
-- forecast next to it (brief section 13).
CREATE TABLE growth_predictions (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    public_id             VARCHAR(36)  NOT NULL,
    patient_id            BIGINT       NOT NULL,
    -- The scan that triggered this analysis, for traceability.
    triggering_scan_id    BIGINT       NULL,
    analysis_job_id       BIGINT       NULL,

    status                VARCHAR(24)  NOT NULL,
    -- Evidence for the sufficiency decision, so the outcome is auditable.
    observation_count     INT          NOT NULL,
    span_days             INT          NULL,

    trend_direction       VARCHAR(16)  NULL,
    -- Forecast series as returned by the model. NULL unless a model ran.
    forecast              JSON         NULL,

    -- NULL when status = 'INSUFFICIENT_HISTORY': no model executed, so there
    -- is no model version to attribute the outcome to.
    model_version_id      BIGINT       NULL,
    preprocessing_version VARCHAR(32)  NULL,
    inference_timestamp   DATETIME(6)  NULL,
    is_synthetic          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            DATETIME(6)  NOT NULL,

    CONSTRAINT pk_growth_predictions PRIMARY KEY (id),
    CONSTRAINT uq_growth_predictions_public_id UNIQUE (public_id),
    CONSTRAINT fk_growth_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id) ON DELETE CASCADE,
    CONSTRAINT fk_growth_triggering_scan
        FOREIGN KEY (triggering_scan_id) REFERENCES scans (id) ON DELETE SET NULL,
    CONSTRAINT fk_growth_job
        FOREIGN KEY (analysis_job_id) REFERENCES analysis_jobs (id) ON DELETE SET NULL,
    CONSTRAINT fk_growth_model_version
        FOREIGN KEY (model_version_id) REFERENCES model_versions (id) ON DELETE RESTRICT,
    CONSTRAINT ck_growth_status
        CHECK (status IN ('COMPLETED', 'INSUFFICIENT_HISTORY', 'FAILED')),
    CONSTRAINT ck_growth_observation_count CHECK (observation_count >= 0),
    CONSTRAINT ck_growth_trend_direction
        CHECK (trend_direction IS NULL
            OR trend_direction IN ('INCREASING', 'DECREASING', 'STABLE', 'INDETERMINATE')),
    -- SAFETY INVARIANT: nothing may be forecast when history is insufficient.
    CONSTRAINT ck_growth_insufficient_history_has_no_forecast
        CHECK (status <> 'INSUFFICIENT_HISTORY'
            OR (forecast IS NULL
                AND trend_direction IS NULL
                AND model_version_id IS NULL
                AND inference_timestamp IS NULL)),
    -- Conversely, a completed estimate must be attributable to a model.
    CONSTRAINT ck_growth_completed_has_model
        CHECK (status <> 'COMPLETED'
            OR (model_version_id IS NOT NULL
                AND inference_timestamp IS NOT NULL
                AND trend_direction IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_growth_patient_created ON growth_predictions (patient_id, created_at);


-- ---------------------------------------------------------------------------
-- reports
-- ---------------------------------------------------------------------------
CREATE TABLE reports (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    public_id               VARCHAR(36)  NOT NULL,
    scan_id                 BIGINT       NOT NULL,
    patient_id              BIGINT       NOT NULL,
    -- Nullable: a report is still issuable when a stage produced no result.
    -- The PDF then states "Not available" rather than omitting the section
    -- (which could read as a negative finding).
    prediction_id           BIGINT       NULL,
    segmentation_result_id   BIGINT      NULL,
    growth_prediction_id    BIGINT       NULL,

    storage_key             VARCHAR(512) NOT NULL,
    content_sha256          VARCHAR(64)  NOT NULL,
    file_size_bytes         BIGINT       NOT NULL,

    -- Explanation provenance. 'UNAVAILABLE' when no provider is configured;
    -- 'REJECTED' when generated text failed safety validation and was
    -- therefore not used (ASSUMPTIONS.md A-3).
    explanation_status      VARCHAR(16)  NOT NULL DEFAULT 'UNAVAILABLE',
    explanation_text        TEXT         NULL,
    explanation_provider    VARCHAR(64)  NULL,
    explanation_model       VARCHAR(128) NULL,

    generated_by_user_id    BIGINT       NOT NULL,
    created_at              DATETIME(6)  NOT NULL,

    CONSTRAINT pk_reports PRIMARY KEY (id),
    CONSTRAINT uq_reports_public_id   UNIQUE (public_id),
    CONSTRAINT uq_reports_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_reports_scan
        FOREIGN KEY (scan_id) REFERENCES scans (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reports_patient
        FOREIGN KEY (patient_id) REFERENCES patients (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reports_prediction
        FOREIGN KEY (prediction_id) REFERENCES predictions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reports_segmentation
        FOREIGN KEY (segmentation_result_id) REFERENCES segmentation_results (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reports_growth
        FOREIGN KEY (growth_prediction_id) REFERENCES growth_predictions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reports_generated_by
        FOREIGN KEY (generated_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_reports_explanation_status
        CHECK (explanation_status IN ('INCLUDED', 'UNAVAILABLE', 'REJECTED')),
    -- Explanation text may only be present when it was actually included.
    CONSTRAINT ck_reports_explanation_consistency
        CHECK ((explanation_status = 'INCLUDED' AND explanation_text IS NOT NULL)
            OR (explanation_status <> 'INCLUDED')),
    CONSTRAINT ck_reports_file_size CHECK (file_size_bytes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_reports_patient_created ON reports (patient_id, created_at);
CREATE INDEX ix_reports_scan            ON reports (scan_id);


-- ---------------------------------------------------------------------------
-- audit_logs  (brief section 27)
-- ---------------------------------------------------------------------------
-- Append-only by design: no updated_at, and the application performs no
-- UPDATE or DELETE against this table.
--
-- PROHIBITED CONTENT: passwords, password hashes, JWTs, refresh tokens, API
-- keys, and patient-identifying free text must never be written here. The
-- `metadata` JSON column is populated from an allow-list of keys, never from
-- an arbitrary request body.
CREATE TABLE audit_logs (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    -- NULL for events with no authenticated principal, e.g. a failed login
    -- against an unknown username.
    user_id       BIGINT       NULL,
    -- Username is denormalised so the audit trail stays readable even if the
    -- user row is later removed.
    username      VARCHAR(64)  NULL,
    action        VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(64)  NULL,
    -- Stores the PUBLIC identifier, never an internal sequential id.
    resource_id   VARCHAR(64)  NULL,
    success       BOOLEAN      NOT NULL,
    -- Textual form accommodates IPv6 (max 45 chars).
    ip_address    VARCHAR(45)  NULL,
    user_agent    VARCHAR(255) NULL,
    -- Correlates an audit entry with application logs (brief section 28).
    trace_id      VARCHAR(64)  NULL,
    metadata      JSON         NULL,
    occurred_at   DATETIME(6)  NOT NULL,

    CONSTRAINT pk_audit_logs PRIMARY KEY (id),
    CONSTRAINT fk_audit_logs_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Query patterns: chronological review; per-user trail; per-action filter;
-- "who touched this resource".
CREATE INDEX ix_audit_logs_occurred  ON audit_logs (occurred_at);
CREATE INDEX ix_audit_logs_user      ON audit_logs (user_id, occurred_at);
CREATE INDEX ix_audit_logs_action    ON audit_logs (action, occurred_at);
CREATE INDEX ix_audit_logs_resource  ON audit_logs (resource_type, resource_id);
CREATE INDEX ix_audit_logs_trace     ON audit_logs (trace_id);
