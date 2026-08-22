# 05 — Database Design

**Version:** 1.1
**Date:** 2026-08-22
**Source of truth:** `backend/src/main/resources/db/migration/V1__baseline_schema.sql`
**Companion:** [`14-ER-DIAGRAM.md`](./14-ER-DIAGRAM.md)

---

## 1. Design philosophy

One principle drives most of the decisions below:

> **Application-layer validation can be bypassed by a future code path. A database
> constraint cannot.**

A new service method, a data-fix script, or a migration written under time pressure can all
skip a Java guard. None of them can skip a CHECK constraint. Because the invariants here
govern *whether a fabricated medical result can be persisted at all*, they belong at the
lowest enforceable level — not only in the service layer where they are convenient.

This is deliberate redundancy: the boundary validation exists for good error messages, the
constraint exists for correctness. They are not duplicates of each other.

---

## 2. Schema-enforced safety invariants

| # | Invariant | Mechanism | Brief |
|---|---|---|---|
| 1 | A forecast cannot accompany an `INSUFFICIENT_HISTORY` outcome | `ck_growth_insufficient_history_has_no_forecast` — forces `forecast`, `trend_direction`, `model_version_id`, `inference_timestamp` all NULL | §13 |
| 2 | A completed trend estimate must be attributable to a model | `ck_growth_completed_has_model` | §22 |
| 3 | Dice/IoU cannot be attached to a production inference | **No such column exists** on `segmentation_results` | §12 |
| 4 | A prediction must carry real model confidence | `confidence NOT NULL` + `ck_predictions_confidence` bounds `[0,1]` | §11 |
| 5 | Development-stub output is always distinguishable | `is_synthetic NOT NULL` on all three inference tables | §46 |
| 6 | Silent failure is impossible | `ck_scans_failure_consistency`, `ck_analysis_jobs_failure_consistency` — a failure code is required iff status is `FAILED` | §39 |
| 7 | "Not detected" cannot carry a size | `ck_segmentation_detection_consistency` | §12 |
| 8 | Archive status and timestamp must agree | `ck_patients_archived_consistency` | §8 |
| 9 | Duplicate analysis is prevented | `uq_analysis_jobs_idempotency (scan_id, idempotency_key)` | §21 |
| 10 | The same file cannot be uploaded twice per patient | `uq_scans_patient_content (patient_id, content_sha256)` | §9 |
| 11 | A refresh token disclosure yields nothing usable | Only `token_hash` (SHA-256) is stored | §7 |
| 12 | The audit trail cannot be deleted by application code | `AuditLogRepository extends Repository`, not `JpaRepository` — no `delete` method exists | §27 |
| 13 | Explanation text exists only when actually included | `ck_reports_explanation_consistency` | §14 |
| 14 | A bounding box is all-or-nothing | `ck_segmentation_bbox_complete` | — |

**Invariant 3 is worth dwelling on.** The strongest guarantee in this schema is expressed by
the *absence* of a column. Dice and IoU require a ground-truth mask, which does not exist for
a production scan. Had the columns been added "for later", some future code path would
eventually populate them with something — and a fabricated accuracy figure attached to a
patient's scan is precisely the failure this project must not have. The evaluation harness
computes them where ground truth genuinely exists; production has nowhere to put them.

---

## 3. Conventions and their reasons

| Convention | Reason |
|---|---|
| Surrogate `BIGINT` PKs, **signed** | Clean Hibernate mapping; `UNSIGNED` causes schema-validation friction for no practical gain |
| Every externally addressable row also has an opaque `public_id` (UUID) | Sequential ids are never exposed in APIs or URLs — prevents enumeration and incidental disclosure of record counts (§8, §26) |
| Enumerations as `VARCHAR` + `CHECK`, not MySQL `ENUM` | Portable, and maps cleanly to `@Enumerated(EnumType.STRING)` under `ddl-auto=validate` |
| `VARCHAR(36)` / `VARCHAR(64)` rather than `CHAR` for UUIDs and digests | Marginally less compact, but avoids Hibernate `char`/`varchar` validation friction. Validation catching real drift is worth more than the bytes |
| `DATETIME(6)`, UTC supplied by the application | No server-timezone dependency; microsecond precision for ordering inference events |
| Optimistic-lock column named `lock_version`, **not** `version` | "Version" is overloaded here — `model_versions.version` is a semantic model version string. Mapping an infrastructure counter to that name produced a real `MappingException` (see §7 below) |
| Timestamps as explicit columns, not triggers | Behaviour stays visible in the application layer rather than hidden in the database |
| Large binaries never stored in-row | MRI files, masks, and PDFs live in file storage; rows hold a `storage_key` plus `content_sha256` (§4) |

---

## 4. Append-only vs. mutable tables

| Append-only | Mutable |
|---|---|
| `predictions` | `users` |
| `segmentation_results` | `patients` |
| `growth_predictions` | `scans` |
| `reports` | `analysis_jobs` |
| `audit_logs` | `model_versions` |

The inference tables are append-only for a specific reason: **re-analysis must not destroy
the record a previously issued report was based on.** If a clinician received a report last
month, the prediction behind it must still exist and still say what it said. Re-running
analysis therefore inserts a new row; it never updates the old one.

`audit_logs` goes further — the entity exposes no mutators at all, and its repository
declares no delete method, so the trail is write-once at the type level.

---

## 5. Transaction boundaries

| Operation | Boundary | Why |
|---|---|---|
| Analysis completion | One transaction covering `predictions` + `segmentation_results` + job status + scan status | §51 — a prediction persisted without its segmentation, or with a stale scan status, is a partially applied result |
| Scan upload | Storage write **then** transactional row insert | An orphaned file is recoverable garbage; a row pointing at a missing file is corrupt data. Order matters |
| Report generation | PDF written to storage, then a single insert linking all result rows | Same reasoning |
| Token refresh | One transaction rotating the old token and inserting the new | Prevents a window where both are valid or neither is |
| Audit write | Same transaction as the audited action where the action is transactional | An action that succeeded without an audit entry is unauditable |

---

## 6. Indexes

Every index maps to a real access path; none exists speculatively. Full table in
[`14-ER-DIAGRAM.md` §4](./14-ER-DIAGRAM.md).

Notable choices:

- **`ix_analysis_jobs_status_queued (status, queued_at)`** — the worker claims the *oldest*
  queued job, so work drains fairly instead of starving an early request.
- **`ix_scans_patient_date (patient_id, scan_date)`** — serves both the patient timeline and
  the ordered series longitudinal analysis consumes, so no in-memory sort is needed.
- **`ix_patients_status_created (status, created_at)`** — a composite rather than a partial
  index, because MySQL has none (see [ADR-004](../ADR/ADR-004-mysql.md)).
- **`ix_audit_logs_trace (trace_id)`** — correlates an audit entry with application log lines
  (§28).

**N+1 avoidance** is handled with explicit `@EntityGraph` on the lookups that always need an
association: `ScanRepository.findWithPatientByPublicId` (authorisation needs the patient),
`PredictionRepository.findFirstByScanOrderByCreatedAtDesc` (every presentation of a
prediction must state its model version), and
`ReportRepository.findWithContextByPublicId` (download authorisation needs patient + scan).

---

## 7. Bug found and fixed during verification

Recorded because it is exactly the class of defect `ddl-auto=validate` exists to catch.

**Symptom.** All 12 integration tests errored on context startup:

```
org.hibernate.MappingException: Column 'version' is duplicated in mapping
for entity 'com.braintwinx.entity.ModelVersion'
```

**Root cause.** `model_versions.version` holds the model's *semantic* version string
(e.g. `1.0.0`). Separately, an optimistic-lock column named `version` was added to every
mutable table for consistency, and `MutableEntity` mapped `@Version` to it. `ModelVersion`
inherits that mapping **and** declares its own `version` column — two properties, one column.

**Fix.** Renamed the optimistic-lock column to `lock_version` across all five mutable tables
and in `MutableEntity`. The domain meaning of `version` stays available where it is natural.

**Why V1 was edited rather than a V2 added.** V1 had never been applied to any persistent
database — only to throwaway test containers — so there was no applied checksum to invalidate
and no deployed schema to migrate. Editing an *already-released* migration would be wrong, and
`validate-on-migrate` is enabled precisely to prevent it.

**Lesson applied.** The collision was invisible to compilation and to unit tests; only starting
a real Hibernate `SessionFactory` surfaced it. This is why integration tests run against real
MySQL and not H2 — and why `ddl-auto` is `validate` in *every* profile including `dev`.

---

## 8. Verification status

Verified by `SchemaMigrationIT` against a real MySQL 8.4 container:

- Flyway records V1 as applied and successful
- All 11 expected tables exist
- `ddl-auto=validate` agrees between all 12 entities and the migrated schema (proven simply by
  the context loading)
- `segmentation_results` has no dice/iou column
- All three inference tables carry `is_synthetic`
- A forecast alongside `INSUFFICIENT_HISTORY` is **rejected**
- An honest `INSUFFICIENT_HISTORY` row **is** storable (the constraint does not block correct
  behaviour)
- An unattributed `COMPLETED` estimate is rejected
- Out-of-range confidence is rejected
- A `FAILED` scan without a reason is rejected
- An inconsistent archive state is rejected
- A duplicate upload for the same patient is rejected
- A guard asserts MySQL ≥ 8.0.16, so these assertions cannot pass vacuously (below that
  version MySQL silently ignores CHECK constraints)

Current pass/fail counts: [`TASKS.md`](../TASKS.md) verification log.
