# 14 — Entity Relationship Diagram

**Version:** 1.0
**Date:** 2026-08-22
**Source of truth:** `backend/src/main/resources/db/migration/V1__baseline_schema.sql`

> This diagram documents the **actual** V1 migration, not an idealised model. If the two
> disagree, the migration is correct and this file is stale — fix it.

---

## 1. Diagram

```mermaid
erDiagram
    users ||--o{ refresh_tokens : "issues"
    users ||--o{ patients : "created_by"
    users ||--o{ scans : "uploaded_by"
    users ||--o{ analysis_jobs : "requested_by"
    users ||--o{ reports : "generated_by"
    users |o--o{ audit_logs : "acted (nullable)"

    patients ||--o{ scans : "has"
    patients ||--o{ growth_predictions : "analysed for"
    patients ||--o{ reports : "subject of"

    scans ||--o{ analysis_jobs : "analysed by"
    scans ||--o{ predictions : "classified as"
    scans ||--o{ segmentation_results : "segmented into"
    scans |o--o{ growth_predictions : "triggered"
    scans ||--o{ reports : "reported on"

    analysis_jobs |o--o{ predictions : "produced"
    analysis_jobs |o--o{ segmentation_results : "produced"
    analysis_jobs |o--o{ growth_predictions : "produced"

    model_versions ||--o{ predictions : "attributed to"
    model_versions ||--o{ segmentation_results : "attributed to"
    model_versions |o--o{ growth_predictions : "attributed to (null if insufficient)"

    predictions |o--o{ segmentation_results : "paired with"
    predictions |o--o{ reports : "included in"
    segmentation_results |o--o{ reports : "included in"
    growth_predictions |o--o{ reports : "included in"

    users {
        bigint id PK
        varchar36 public_id UK "opaque; sequential id never exposed"
        varchar64 username UK
        varchar255 email UK
        varchar100 password_hash "BCrypt; never logged"
        varchar16 role "CHECK ADMIN|DOCTOR|RESEARCHER"
        boolean enabled
        int failed_login_attempts "CHECK >= 0"
        datetime6 locked_until "null unless locked"
        bigint version "optimistic lock"
    }

    refresh_tokens {
        bigint id PK
        bigint user_id FK
        varchar64 token_hash UK "SHA-256 only; token never stored"
        datetime6 expires_at "CHECK > issued_at"
        datetime6 revoked_at
        bigint replaced_by FK "rotation chain -> replay detection"
    }

    patients {
        bigint id PK
        varchar32 patient_code UK "only identifier used in APIs"
        smallint birth_year "year only; CHECK 1900-2200"
        varchar16 sex "CHECK MALE|FEMALE|OTHER|UNKNOWN"
        varchar16 status "CHECK ACTIVE|ARCHIVED"
        datetime6 archived_at "CHECK must agree with status"
        bigint created_by_user_id FK
    }

    model_versions {
        bigint id PK
        varchar128 model_name "UK with version"
        varchar16 model_type "CHECK CLASSIFIER|SEGMENTER|FORECASTER"
        varchar32 version
        varchar64 framework
        varchar64 checksum_sha256 "mismatch = refuse to load"
        varchar64 input_shape
        varchar32 preprocessing_version "part of model identity"
        varchar16 status "CHECK ACTIVE|INACTIVE|DEPRECATED"
    }

    scans {
        bigint id PK
        varchar36 public_id UK
        bigint patient_id FK
        date scan_date
        varchar16 scan_type "CHECK MRI_T1|T1C|T2|FLAIR|OTHER"
        varchar512 storage_key UK "server-generated; never from client"
        varchar255 original_filename "display only; never a path"
        varchar100 detected_mime_type "from magic bytes, not client header"
        bigint file_size_bytes "CHECK > 0"
        varchar64 content_sha256 "UK with patient_id"
        varchar16 status "CHECK 7-state lifecycle"
        varchar64 failure_code "CHECK required iff FAILED"
        bigint uploaded_by_user_id FK
    }

    analysis_jobs {
        bigint id PK
        varchar36 public_id UK
        bigint scan_id FK
        varchar128 idempotency_key "UK with scan_id -> no duplicate analysis"
        varchar16 status "CHECK QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED"
        smallint progress_percent "CHECK 0-100"
        int attempt_count "CHECK >= 0"
        varchar64 error_code "CHECK required iff FAILED"
        datetime6 started_at "stale-job reaper input"
    }

    predictions {
        bigint id PK
        varchar36 public_id UK
        bigint scan_id FK
        bigint analysis_job_id FK
        varchar64 predicted_class
        decimal confidence "NOT NULL, CHECK 0-1, real model output"
        json probabilities "full distribution, not just argmax"
        bigint model_version_id FK
        varchar32 preprocessing_version
        datetime6 inference_timestamp
        boolean is_synthetic "SAFETY: stub output flag"
    }

    segmentation_results {
        bigint id PK
        varchar36 public_id UK
        bigint scan_id FK
        bigint prediction_id FK
        boolean tumor_detected
        varchar512 mask_storage_key "artefact, not a blob"
        bigint tumor_area_px "PIXELS of preprocessed image, not mm2"
        int mask_width
        int bbox_x "CHECK box is all-or-nothing"
        bigint model_version_id FK
        boolean is_synthetic
    }

    growth_predictions {
        bigint id PK
        varchar36 public_id UK
        bigint patient_id FK
        bigint triggering_scan_id FK
        varchar24 status "CHECK COMPLETED|INSUFFICIENT_HISTORY|FAILED"
        int observation_count "evidence for the decision"
        int span_days
        varchar16 trend_direction "null if insufficient"
        json forecast "CHECK null if INSUFFICIENT_HISTORY"
        bigint model_version_id FK "CHECK null if INSUFFICIENT_HISTORY"
        boolean is_synthetic
    }

    reports {
        bigint id PK
        varchar36 public_id UK
        bigint scan_id FK
        bigint patient_id FK
        bigint prediction_id FK "nullable -> section reads Not available"
        bigint segmentation_result_id FK "nullable"
        bigint growth_prediction_id FK "nullable"
        varchar512 storage_key UK
        varchar64 content_sha256 "detects post-issue alteration"
        varchar16 explanation_status "CHECK INCLUDED|UNAVAILABLE|REJECTED"
        text explanation_text "CHECK present iff INCLUDED"
        bigint generated_by_user_id FK
    }

    audit_logs {
        bigint id PK
        bigint user_id FK "null for failed login"
        varchar64 username "denormalised; survives user removal"
        varchar64 action
        varchar64 resource_type
        varchar64 resource_id "PUBLIC id, never internal"
        boolean success
        varchar45 ip_address "IPv6-capable"
        varchar64 trace_id "correlates with app logs"
        json metadata "allow-listed keys only"
        datetime6 occurred_at
    }
```

---

## 2. Cardinality notes

| Relationship | Cardinality | Why |
|---|---|---|
| `scans` → `predictions` | 1:N, **not 1:1** | Predictions are append-only. Re-analysis inserts a row so an already-issued report stays explainable by the record behind it. |
| `scans` → `analysis_jobs` | 1:N | A failed job may be retried; each attempt is a distinct row with its own idempotency key. |
| `predictions` → `segmentation_results` | 1:0..N | Segmentation usually accompanies a classification but is independently addressable, since a segmentation model may run without a classifier. |
| `patients` → `growth_predictions` | 1:N | Longitudinal analysis is per patient, not per scan. `triggering_scan_id` records what prompted it. |
| `model_versions` → `growth_predictions` | 0..1:N | **Nullable on purpose.** An `INSUFFICIENT_HISTORY` outcome has no model attribution because no model executed. |
| `users` → `audit_logs` | 0..1:N | Nullable so a failed login against an unknown username is still auditable. |

---

## 3. Referential-action rationale

`ON DELETE` is chosen per relationship rather than uniformly, because "what should happen when
the parent goes away" has different correct answers here.

| Child → Parent | Action | Reason |
|---|---|---|
| `refresh_tokens` → `users` | `CASCADE` | Tokens are worthless without their user and carry no audit value. |
| `patients` → `users` | `RESTRICT` | A user who created patient records cannot be hard-deleted; that would erase provenance. |
| `scans` → `patients` | `RESTRICT` | Patients are soft-archived, never deleted (A-13), so this should never fire. It exists to make a mistaken hard delete fail loudly. |
| `predictions` → `scans` | `CASCADE` | A prediction is meaningless without its scan. |
| `predictions` → `model_versions` | `RESTRICT` | A model version referenced by any result must survive, or the result loses its provenance (§22). |
| `reports` → all results | `RESTRICT` | An issued report must remain reconstructible from its inputs. |
| `audit_logs` → `users` | `SET NULL` | The trail outlives the user; `username` is denormalised to keep it readable. |
| `segmentation_results` → `predictions` | `SET NULL` | The segmentation retains standalone meaning if its paired prediction is removed. |

---

## 4. Indexes and the query patterns that justify them

No index exists speculatively; each maps to a real access path.

| Index | Query it serves |
|---|---|
| `ix_scans_patient_date` | Patient timeline; the ordered series longitudinal analysis consumes |
| `ix_scans_status_created` | Dashboard counts of pending / processing / completed work |
| `ix_analysis_jobs_status_queued` | Worker claiming the oldest queued job (fair drain, no starvation) |
| `uq_analysis_jobs_idempotency` | Idempotency lookup on repeated analyse requests (§21) |
| `uq_scans_patient_content` | Duplicate-upload detection |
| `ix_predictions_scan_created` | Latest prediction for a scan |
| `ix_growth_patient_created` | Latest longitudinal outcome for a patient |
| `ix_reports_patient_created` | Paginated report list per patient |
| `ix_audit_logs_*` (5) | Chronological review, per-user trail, per-action filter, per-resource lookup, trace correlation |

`patients` uses a composite `(status, created_at)` rather than a partial index, since MySQL has
no partial indexes (see [ADR-004](../ADR/ADR-004-mysql.md)).

---

## 5. Verification status

⚠️ **This schema has not yet been applied to a running database.** The migration and the 9
constraint tests in `SchemaMigrationIT` are written but unexecuted — blocked on B-6 in
[`TASKS.md`](../TASKS.md). Treat this document as *designed and reviewed*, not *verified*.
