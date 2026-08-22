# ADR-004: MySQL as the primary datastore

**Status:** Accepted
**Date:** 2026-08-22
**Deciders:** Engineering owner
**Supersedes:** —

---

## Context

BrainTwinX stores users, patients, scans, predictions, segmentation metadata, growth
analyses, reports, audit logs, and a model registry. The data is highly relational: a
report references a scan, a prediction, a segmentation result, a growth analysis, a
patient, and a user, and every inference must be attributable to an exact model version.

The project brief names MySQL (§4, §16). A MySQL 8.0.46 installer is present on the host,
though the server is not installed and the client is not on `PATH`.

## Problem

Which datastore should own durable state, and how much of the domain's correctness should be
enforced by it rather than by application code?

## Options considered

| Option | Assessment |
|---|---|
| **MySQL 8** | Named in the brief. Mature, ubiquitous, supports CHECK constraints (8.0.16+), JSON columns, and generated columns. Weaker than PostgreSQL on partial indexes, expression indexes, and JSON querying. |
| **PostgreSQL** | Technically stronger for this workload: richer constraint support, better JSON, partial indexes. But it contradicts an explicit brief requirement, and brief §58 says not to invent requirements — the correct response to a specified technology is to use it, not to substitute a preference. |
| **MongoDB** | Rejected outright. The domain is relational and correctness depends on foreign keys and multi-row constraints; a document store would push all referential integrity into application code, which is the opposite of the direction this project needs. |
| **SQLite** | Adequate for local development only. No concurrent-write story for a multi-user API. |

## Decision

Use **MySQL 8** (containerised for local development), with **Flyway** owning the schema and
Hibernate configured `ddl-auto=validate` in every profile.

The significant decision is not the vendor but **how much correctness lives in the
database**: medical-safety invariants are enforced as CHECK constraints, not only in Java.

## Reason

MySQL is specified, and it is capable of everything the schema needs. The more consequential
choice is constraint placement, and the reasoning is:

**Application-layer validation can be bypassed by a future code path; a database constraint
cannot.** A new service method, a data-fix script, or a migration written under time
pressure can all skip a Java guard. They cannot skip a CHECK constraint. Given that the
invariants concern whether a fabricated medical result can be persisted, they belong at the
lowest enforceable level.

Invariants placed in the schema:

| Invariant | Constraint |
|---|---|
| A forecast cannot accompany an `INSUFFICIENT_HISTORY` outcome (brief §13) | `ck_growth_insufficient_history_has_no_forecast` |
| A completed trend estimate must be attributable to a model version | `ck_growth_completed_has_model` |
| Confidence must be non-null and within [0,1] | `NOT NULL` + `ck_predictions_confidence` |
| Dice/IoU cannot be attached to a production inference (brief §12) | **no such column exists** |
| A failed scan must record why | `ck_scans_failure_consistency` |
| Archive status and archive timestamp must agree | `ck_patients_archived_consistency` |
| "Not detected" cannot carry an area or bounding box | `ck_segmentation_detection_consistency` |
| Development-stub output is always distinguishable | `is_synthetic NOT NULL` on all three inference tables |
| The same file cannot be uploaded twice per patient | `uq_scans_patient_content` |
| Duplicate analysis is prevented | `uq_analysis_jobs_idempotency` |

Supporting choices:

- **Enumerations are `VARCHAR` + `CHECK`, not MySQL `ENUM`** — portable, and maps cleanly to
  `@Enumerated(EnumType.STRING)` under `validate`.
- **`VARCHAR` rather than `CHAR`** for UUIDs and SHA-256 digests. Marginally less compact,
  but avoids Hibernate schema-validation friction over `char`/`varchar`, and validation
  catching real drift is worth more than the bytes.
- **Flyway `clean` is disabled** in all profiles. A command that drops a medical schema
  should not be reachable.
- **Large binaries are never stored in the database.** MRI files, masks, and PDFs live in
  file storage; rows hold storage keys and content hashes (brief §4).
- **`validate` in dev too.** Allowing Hibernate to mutate the local schema would let
  entity/migration drift go unnoticed until it reached an environment where it mattered.

## Trade-offs

**Accepted:**

- MySQL's JSON querying is weaker than PostgreSQL's. Acceptable: JSON columns here hold
  opaque payloads (probability distributions, forecast series, audit metadata) that are read
  whole, not queried into.
- No partial indexes, so "index only active patients" is expressed as a composite
  `(status, created_at)` index instead.
- CHECK constraints require **8.0.16 or later**. On earlier versions they parse but are
  *silently ignored*, which would make the safety tests pass vacuously. Mitigated by an
  integration test that asserts the server version and fails loudly if it is too old.
- Constraint violations surface as `DataIntegrityViolationException` rather than friendly
  validation errors, so the application must still validate at the boundary for good
  messages. This is duplication by design: the boundary check is for usability, the
  constraint is for correctness.

## Consequences

- Local development uses a MySQL **container**; no host MySQL install is required
  (`CURRENT_STATE.md` risk R-7).
- Integration tests run against **real MySQL via Testcontainers, never H2** — an in-memory
  substitute would not enforce the CHECK constraints these tests exist to verify.
- Schema changes are always forward migrations; migrations are never edited after being
  applied (`validate-on-migrate` enforces this).
- Adding a new entity field requires a migration, or the build fails at context startup.

## Verification

- 11 tables created and asserted present — see `SchemaMigrationIT`.
- Each safety invariant above has a test that attempts the forbidden write and asserts
  rejection.
- Server-version guard prevents vacuous passes on pre-8.0.16.
- Current pass/fail status: `docs/TASKS.md`.
