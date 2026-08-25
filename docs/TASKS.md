# BrainTwinX — Task Tracking

**Last updated:** 2026-08-22
**Legend:** `[ ]` not started · `[-]` in progress · `[x]` completed · `[!]` blocked

**Feature status ladder** (brief §46) — a feature is `VERIFIED` only after implementation,
tests, integration, and manual verification where applicable:

`NOT_STARTED` → `IN_PROGRESS` → `IMPLEMENTED` → `TESTED` → `VERIFIED`

> **Rule:** nothing in this file is marked `[x]` or `VERIFIED` on the strength of code
> having been written. Only demonstrated behaviour counts.

---

## Phase status overview

| Phase | Name | Status |
|---|---|---|
| P1 | Repository audit & scaffold | `[x]` **COMPLETE** |
| P2 | Architecture & database | `[x]` **COMPLETE** — `mvn verify` green, 45 tests |
| P3 | Authentication & authorisation | `[x]` **COMPLETE** — `mvn verify` green, 94 tests |
| P4 | Patient management | `[x]` **COMPLETE** — `mvn verify` green, 146 tests |
| P5 | MRI upload & validation | `[ ]` NOT_STARTED |
| P6 | AI service foundation | `[ ]` NOT_STARTED |
| P7 | CNN classification | `[ ]` NOT_STARTED |
| P8 | U-Net segmentation | `[ ]` NOT_STARTED |
| P9 | Longitudinal / LSTM | `[ ]` NOT_STARTED |
| P10 | Explanation layer | `[ ]` NOT_STARTED |
| P11 | Report generation | `[ ]` NOT_STARTED |
| P12 | Frontend integration | `[ ]` NOT_STARTED |
| P13 | Testing | `[ ]` NOT_STARTED |
| P14 | Security hardening | `[ ]` NOT_STARTED |
| P15 | Docker & deployment | `[ ]` NOT_STARTED |
| P16 | Documentation & acceptance | `[ ]` NOT_STARTED |

---

## P1 — Repository audit & scaffold

**Status: COMPLETE.** Committed as `884014d`.

- [x] Inspect target directory — verified **empty** (0 items, recursive, incl. hidden)
- [x] Confirm no Git repository present
- [x] Search host filesystem for existing BrainTwinX source or archives — none found
- [x] Read all prior assistant session transcripts — no code was ever authored
- [x] Inventory host toolchain (JDK 25.0.4, Maven 3.9.16, Node 24.16.0, Python 3.14.5, Docker 29.6.2, Git 2.45.1)
- [x] Verify PyTorch cp314 wheel availability (torch 2.13.0) — local ML dev viable
- [x] Confirm greenfield vs. existing-code decision with project owner
- [x] Confirm dataset availability with project owner — **none available**
- [x] `git init` (branch `main`)
- [x] Create directory scaffold per brief §5
- [x] `docs/CURRENT_STATE.md`
- [x] `docs/IMPLEMENTATION_PLAN.md`
- [x] `docs/TASKS.md`
- [x] `docs/ASSUMPTIONS.md`
- [x] `.gitignore` covering build output, `node_modules`, virtualenvs, `.env`, model weights, uploaded scans, generated reports — **DONE, and verified functionally**: `git check-ignore` confirms `.env`, `storage/patient.png`, and `*.pt` weights are all excluded
- [x] `.env.example` with placeholders only — **no real secrets**
- [x] `README.md` initial skeleton
- [x] Verify no secrets tracked (`git ls-files` review + secret-pattern scan over staged diff)
- [x] Initial commit (`884014d`)

**Exit criteria met: YES.**

---

## P2 — Architecture & database

**Status: COMPLETE.** `mvn verify` **BUILD SUCCESS** — 45 tests (32 unit + 13 integration
against real MySQL 8.4), 0 failures, 0 errors. Exit criteria met.

### Completed and verified

- [x] Spring Boot project (`pom.xml`) — Spring Boot 4.1.1, Java 21 target, no Lombok
- [x] **Verified** Spring Boot ↔ Java 25 compatibility by running an actual build:
      `mvn test-compile` compiled 38 main + 3 test sources with `release 21` and **zero
      warnings** on project sources
- [x] Flyway `V1__baseline_schema.sql` — 11 tables with PK/FK/UNIQUE/INDEX/NOT NULL/CHECK
- [x] JPA entities (12) + base classes + 12 domain enums
- [x] Repositories (11)
- [x] Scan status state machine defined in one authoritative place (`ScanStatus`)
- [x] Job status state machine (`JobStatus`)
- [x] Model-version and preprocessing-version columns present from the outset
- [x] `application.yml` + `application-dev.yml` + `application-prod.yml`
- [x] **32 unit tests passing** — `mvn test` BUILD SUCCESS, 0 failures, 0 errors
      (state machines, illegal-transition rejection, mandatory failure codes, retry
      clearing stale errors)
- [x] ADR-001 React frontend
- [x] ADR-002 Spring Boot backend
- [x] ADR-003 Python AI service
- [x] ADR-004 MySQL
- [x] ADR-005 REST for backend↔AI communication
- [x] `docs/TROUBLESHOOTING.md` — 7 entries, each with diagnosed root cause

### Verified against real MySQL 8.4

- [x] **`mvn verify` — BUILD SUCCESS**, 45 tests, 0 failures, 0 errors
- [x] Flyway applies V1 from an empty schema and records it successful
- [x] All 11 expected tables created
- [x] Hibernate `ddl-auto=validate` **agrees** between all 12 entities and the migration
      (proven by the context loading at all)
- [x] `segmentation_results` has **no** dice/iou column
- [x] All three inference tables carry `is_synthetic`
- [x] A forecast alongside `INSUFFICIENT_HISTORY` is **rejected**
      (`ck_growth_insufficient_history_has_no_forecast`)
- [x] An honest `INSUFFICIENT_HISTORY` row **is** storable — the constraint does not block
      correct behaviour
- [x] An unattributed `COMPLETED` estimate is rejected (`ck_growth_completed_has_model`)
- [x] Out-of-range confidence is rejected (`ck_predictions_confidence`)
- [x] In-range confidence **is** storable
- [x] A `FAILED` scan without a reason is rejected (`ck_scans_failure_consistency`)
- [x] An inconsistent archive state is rejected (`ck_patients_archived_consistency`)
- [x] A duplicate upload for the same patient is rejected
- [x] MySQL ≥ 8.0.16 guard, so no assertion can pass vacuously
- [x] Maven Wrapper committed and **verified to build with no host Maven on PATH**
- [x] `docs/system-design/01-SYSTEM-OVERVIEW.md`
- [x] `docs/system-design/02-HIGH-LEVEL-DESIGN.md`
- [x] `docs/system-design/05-DATABASE-DESIGN.md`
- [x] `docs/system-design/14-ER-DIAGRAM.md`

**Exit criteria met: YES.** The schema has been applied to a real MySQL instance, every
entity mapping validated against it, and all 14 medical-safety invariants exercised by tests
that attempt the forbidden write and assert the specific constraint name.


---

## P3 — Authentication & authorisation

**Status: COMPLETE.** `mvn verify` **BUILD SUCCESS** — 94 tests (57 unit + 37 integration),
0 failures, 0 errors.

- [x] User entity + roles ADMIN / DOCTOR / RESEARCHER (from P2)
- [x] BCrypt password hashing — strength 12, above the Spring default of 10
- [x] JWT issue + validate — HS256 fixed at both ends, issuer verified
- [x] Access + refresh flow with **rotation** and reuse detection
- [x] `SecurityFilterChain` — **`anyRequest().denyAll()`**, not `authenticated()`
- [x] `POST /api/v1/auth/login` · `refresh` · `logout`
- [x] `GlobalExceptionHandler` + `ApiError` envelope with `traceId`
- [x] `ApiErrorCode` — closed enum, each carrying its HTTP status
- [x] `CorrelationIdFilter` — validates and length-caps inbound trace IDs
- [x] `AuditService` — **allow-listed** metadata keys, `REQUIRES_NEW` propagation
- [x] Audit events LOGIN / LOGOUT / LOGIN_FAILED / TOKEN_REFRESHED
- [x] Security headers: nosniff, DENY, HSTS, CSP, no-referrer
- [x] CORS restricted to an explicit origin list, never a wildcard
- [x] ADR-006 JWT authentication

### Verified by test

- [x] Unauthenticated → 401 with the standard envelope, **no stack trace**, and asserted
      free of `Exception` / `com.braintwinx` / `org.springframework`
- [x] Expired token → 401 `TOKEN_EXPIRED`, distinguishable from `TOKEN_INVALID`
- [x] **Deny-by-default proven**: 8 unmapped / unimplemented / internal paths all denied,
      including `/internal/**` and `/actuator/env`
- [x] `alg:none` forged token rejected
- [x] Token signed with a different key rejected
- [x] Token from a different issuer rejected even when validly signed
- [x] Refresh token replayed as an access token rejected (`typ` claim)
- [x] Unknown role in a token rejected, never defaulted
- [x] 6 malformed token shapes rejected
- [x] Login failure is **uniform** — body asserted not to contain `username`, `password`,
      `not found`, or `unknown`
- [x] Trace ID present on every response, including rejected ones
- [x] Hostile CRLF trace ID replaced, not echoed (log-injection defence)
- [x] Well-formed inbound trace ID honoured for cross-service correlation
- [x] Token responses carry `Cache-Control: no-store`
- [x] JWT secret validation: missing, too short, and 3 placeholder shapes all abort startup
- [x] `JwtProperties.toString()` redacts the secret
- [x] Refresh tokens unique across 500 draws; hashing deterministic and non-reversing

### Deferred with reason

- [ ] Rate limiting on `/auth/login` — Phase 14. Lockout after 5 failures for 15 minutes is
      in place now; lockout is **temporary** on purpose, since a permanent lock would let an
      attacker deny a clinician access to patient records.
- [ ] Role-specific endpoint rules — deferred to the phases that add those endpoints, since
      `denyAll()` means each must grant access explicitly anyway.

**Exit criteria met: YES.**

---

## P4 — Patient management

**Status: COMPLETE.** `mvn verify` **BUILD SUCCESS** — **146 tests** (72 unit + 74 integration),
**0 failures, 0 errors**.

- [x] Patient entity with public-safe `patientCode` (from P2)
- [x] DTOs: `PatientCreateRequest`, `PatientUpdateRequest`, `PatientResponse`, `PageResponse`
- [x] `PatientMapper` — hand-written, one-way, so adding an entity field cannot silently
      start exposing it
- [x] `PatientService` — all access-scope enforcement lives here, not the controller
- [x] `PatientController` — thin; `@PreAuthorize` is a coarse role gate only
- [x] Repository scoping via `findByCreatedByAndStatus`, with `@EntityGraph` on the
      security-critical path to avoid an N+1 on every scope check
- [x] Create / read / update / archive (soft) + paginated listing
- [x] Bean Validation with field-level error responses
- [x] Duplicate `patientCode` → 409
- [x] RBAC: ADMIN unrestricted · DOCTOR own caseload · RESEARCHER read-only (ADR-007)
- [x] Audit events PATIENT_CREATED / VIEWED / UPDATED / ARCHIVED, plus failed creates
- [x] `ConstraintViolationException` handler added — request-parameter violations were
      escaping as 500
- [x] ADR-007 patient access scope
- [x] ASSUMPTIONS.md A-16 (caseload scope), A-17 (immutable patient code)

### Verified by test

**IDOR (the central assertion):**
- [x] Doctor B reading Doctor A's patient → **404, not 403**
- [x] The denied response is asserted **identical** to a genuinely-absent one
- [x] Doctor B cannot update or archive Doctor A's patient
- [x] A doctor's listing contains only their own patients — scoped **in the query**
- [x] ADMIN and RESEARCHER have unrestricted read

**PHI leakage:**
- [x] A denied response contains no `birthYear`, `sex`, or field names
- [x] A validation failure names the field but does **not** echo the rejected value
- [x] Responses omit the internal id, `createdBy`, and `lockVersion`
- [x] The creating clinician's username never appears in a response

**Validation:**
- [x] Blank code → 400 with `fieldErrors[0].field == patientCode`
- [x] 7 malformed codes rejected (path traversal, spaces, slashes, quotes, semicolon,
      leading hyphen)
- [x] 4 out-of-range birth years rejected
- [x] Unknown body field rejected rather than silently ignored
- [x] Oversized page size → 400 (was 500 before the handler was added)

**State and lifecycle:**
- [x] Archive is soft — record still retrievable with `status=ARCHIVED`
- [x] Archive is idempotent, and a repeat does **not** add a second audit row
- [x] An archived patient cannot be updated → 409
- [x] Archived records excluded from the default listing
- [x] Partial update leaves omitted fields unchanged
- [x] `sex` defaults to `UNKNOWN` rather than being inferred

**Cross-cutting:**
- [x] Audit rows record the **public** code, never an internal id
- [x] Failed create audited with `success = FALSE`
- [x] Trace ID present on responses and in the error envelope
- [x] A principal that no longer resolves to an enabled user is rejected

**Exit criteria met: YES.**

---

## P5 — MRI upload, validation & storage

- [ ] `StorageService` port + local filesystem implementation
- [ ] Multipart upload endpoint
- [ ] Extension allow-list
- [ ] Magic-byte signature validation (declared MIME never trusted)
- [ ] Size cap + decompressed-size / dimension limits
- [ ] Corruption / readability check
- [ ] Server-generated storage names (no user input in paths)
- [ ] SHA-256 content hash
- [ ] Scan record with status `UPLOADED`
- [ ] Audit event SCAN_UPLOADED
- [ ] Test: rejects wrong extension
- [ ] Test: rejects spoofed MIME
- [ ] Test: rejects magic-byte mismatch
- [ ] Test: rejects zero-byte and oversized files
- [ ] Test: rejects truncated / corrupt image
- [ ] Test: rejects path traversal (`../`, absolute, NUL byte, Windows reserved names)
- [ ] Test: rejects decompression bomb

---

## P6 — AI service foundation

- [ ] FastAPI application skeleton
- [ ] Startup configuration validation
- [ ] Model registry abstraction
- [ ] Models loaded **once per worker**
- [ ] `GET /internal/ai/v1/health` (liveness)
- [ ] `GET /internal/ai/v1/ready` (readiness — false unless models loaded + shape-validated)
- [ ] Deterministic preprocessing pipeline with `preprocessingVersion`
- [ ] Pydantic request/response schemas
- [ ] Backend→AI client with timeout, retry policy, typed failure mapping
- [ ] `AI_SERVICE_UNAVAILABLE` handled without hanging the request
- [ ] Test: NOT READY when weights absent
- [ ] Test: preprocessing deterministic for fixed input + version
- [ ] Test: malformed and hostile payloads rejected by schema

---

## P7 — CNN classification

- [ ] `Classifier` interface
- [ ] PyTorch loader with checksum verification + input-shape assertion
- [ ] `PredictionResult` with real confidence + full probability distribution
- [ ] Model identity recorded (`modelName`, `modelVersion`, `preprocessingVersion`, `inferenceTimestamp`)
- [ ] Transactional persistence of prediction
- [ ] Idempotency key — repeated `POST /analyze` does not duplicate
- [ ] Async job + `GET /scans/{id}/status` polling
- [ ] Training script (runnable once data exists)
- [ ] Evaluation harness (accuracy, precision, recall, F1, ROC-AUC, confusion matrix)
- [ ] Patient-level split to prevent leakage
- [ ] `docs/DATASET_SETUP.md`
- [ ] Test: no hardcoded confidence anywhere
- [ ] Test: with no weights → typed error, **no invented prediction**
- [ ] Test: development stub is OFF by default and labelled non-clinical when on
- [ ] Test: illegal status transitions rejected
- [!] Trained model weights — **BLOCKED: no dataset available** (`ASSUMPTIONS.md` A-1)
- [!] Reported accuracy metrics — **BLOCKED: requires a real evaluation run**

---

## P8 — U-Net segmentation

- [ ] `Segmenter` interface
- [ ] Mask persisted as artefact (not a DB blob)
- [ ] `SegmentationResult` with `tumorDetected`, `tumorArea`, dimensions, bounding box
- [ ] Documented area units
- [ ] Dice / IoU **in the evaluation harness only**
- [ ] Test: no Dice/IoU on a production inference response
- [ ] Test: original scan bytes never mutated
- [!] Trained segmentation weights — **BLOCKED: no dataset available**

---

## P9 — Longitudinal analysis & LSTM

- [ ] Patient history assembly
- [ ] Minimum-observation + minimum-time-span policy
- [ ] `INSUFFICIENT_HISTORY` typed response
- [ ] LSTM forecaster interface
- [ ] Output labelled `MODEL-BASED TREND ESTIMATE`
- [ ] Evaluation metrics (MAE, RMSE, MAPE)
- [ ] Test: 0, 1, and *n−1* observations → `INSUFFICIENT_HISTORY`, nothing fabricated
- [ ] Test: no wording implies certainty about future growth
- [!] Trained forecasting weights — **BLOCKED: no longitudinal dataset available**

---

## P10 — Explanation layer

- [ ] `ExplanationProvider` port (provider-agnostic)
- [ ] Allow-listed structured input only — **image never sent to the LLM**
- [ ] System prompt encoding all ten prohibitions (brief §14)
- [ ] Post-generation validator (invented findings, measurements, symptoms, history, certainty, unsupported recommendations)
- [ ] Fail closed when unconfigured
- [ ] Persist only after validation passes
- [ ] Test: one case per prohibition class
- [ ] Test: unconfigured → typed error, pipeline still completes
- [ ] Test: prompt-injection attempt via free-text field cannot reach the prompt
- [!] Live provider — **BLOCKED: no LLM API key configured** (`ASSUMPTIONS.md` A-3)

---

## P11 — Report generation

- [ ] PDF renderer
- [ ] All eleven sections (brief §15)
- [ ] Mandatory disclaimer, unconditional
- [ ] Model + preprocessing versions printed
- [ ] Storage + `GET /reports/{id}/download`
- [ ] Audit events REPORT_GENERATED / REPORT_ACCESSED
- [ ] Test: missing stages render "Not available", never blank or invented
- [ ] Test: download authorisation (IDOR)

---

## P12 — Frontend integration

- [ ] Vite + React + TypeScript project
- [ ] Routes: `/login` `/dashboard` `/patients` `/patients/:id` `/patients/:id/scans` `/scans/upload` `/scans/:id` `/scans/:id/results` `/reports` `/reports/:id` `/profile`
- [ ] Protected routing + role-based UI
- [ ] Loading / skeleton / empty / error / success states
- [ ] Confirmation dialogs
- [ ] Form validation mirroring backend rules
- [ ] MRI viewer: overlay, opacity, zoom, pan, toggle mask
- [ ] Status polling
- [ ] Persistent medical disclaimer
- [ ] Formal language review against brief §44
- [ ] Test: no fake patient data on any production path
- [ ] Test: absent / processing / failed analyses render brief §34 copy exactly
- [ ] Lint + build clean
- [ ] Accessibility: keyboard navigation, labelled controls, contrast

---

## P13 — Testing

- [ ] `docs/TESTING_STRATEGY.md`
- [ ] E2E journey: login → patient → upload → analyse → status → result → report → download
- [ ] Failure injection: DB down
- [ ] Failure injection: AI service down
- [ ] Failure injection: storage failure
- [ ] Failure injection: expired JWT
- [ ] Failure injection: model unavailable
- [ ] Failure injection: report generation failure
- [ ] Every brief §39 scenario reaches a predictable state

---

## P14 — Security hardening

- [ ] Rate limiting
- [ ] Security headers
- [ ] CORS restricted to known origins
- [ ] Dependency vulnerability scan
- [ ] Secret scan across full Git history
- [ ] `docs/SECURITY.md` + `system-design/08-SECURITY-DESIGN.md`
- [ ] Every brief §54 threat mapped to a mitigation **with evidence**

---

## P15 — Docker & deployment

- [ ] `docker/Dockerfile.frontend`
- [ ] `docker/Dockerfile.backend`
- [ ] `docker/Dockerfile.ai-service`
- [ ] `docker-compose.yml` — no committed secrets
- [ ] MySQL container + healthcheck-gated startup ordering
- [ ] Pinned Python version in AI image (host-independent)
- [ ] `docs/DEPLOYMENT.md`
- [ ] Verify: `docker compose up` works from clean checkout with no local MySQL

---

## P16 — Documentation & final acceptance

- [ ] `README.md` complete (all brief §42 sections)
- [ ] `docs/ARCHITECTURE.md` · `HLD.md` · `LLD.md` · `API.md` · `DATABASE.md` · `AI_PIPELINE.md` · `MODEL_EVALUATION.md` · `TROUBLESHOOTING.md` · `LIMITATIONS.md`
- [ ] `docs/system-design/` — all 16 documents
- [ ] Mermaid diagrams: component, class, sequence ×5, activity, state, deployment, ER
- [ ] OpenAPI/Swagger published and accurate
- [ ] Docs and implementation verified non-contradictory
- [ ] Brief §59 acceptance checklist walked item by item, each marked with its **verified** status

---

## Blocked items summary

| ID | Item | Reason | Unblocked by |
|---|---|---|---|
| B-1 | Trained classification weights | No MRI dataset | Owner supplies a licensed dataset |
| B-2 | Trained segmentation weights | No annotated masks | Owner supplies a segmentation dataset |
| B-3 | Trained forecasting weights | No longitudinal series | Owner supplies serial-scan data |
| B-4 | Reported model accuracy metrics | No evaluation run possible | B-1/B-2/B-3 resolved |
| B-5 | Live LLM explanations | No provider/API key configured | Owner supplies provider config |
| ~~B-6~~ | ~~Schema verification (`mvn verify`)~~ — **RESOLVED**: `mysql:8.4` pulled on retry attempt 8 (Docker caches completed layers, so repeated attempts made incremental progress). | **Cannot pull the `mysql:8.4` Docker image.** 12 pull attempts failed, and the AWS ECR public mirror fails identically on a different CDN host with `httpReadSeeker: failed open: ... EOF` from Docker Hub's CDN. Docker itself works (`hello-world` runs; `testcontainers/ryuk:0.12.0` pulled successfully and its container was created), and the Testcontainers↔Docker connection defect was found and fixed. The only remaining obstacle is downloading the ~250 MB database image. | A successful `docker pull mysql:8.4` on a stable connection. Then `mvn verify` runs unchanged — no code change required. |

These are **documented gaps, not silent omissions.** The corresponding interfaces,
pipelines, and harnesses are still built and tested so that resolving each blocker is a
configuration/data step rather than a development step.

---

## Verification log

A record of what has actually been executed, so no status above rests on assumption.

| Date | Check | Result |
|---|---|---|
| 2026-08-22 | Repository audit (9 independent checks) | Repo empty — greenfield confirmed |
| 2026-08-22 | `pip index versions torch` | 2.13.0 available for cp314 — local ML dev viable |
| 2026-08-22 | `git check-ignore` on `.env`, PHI file, `.pt` weights | All correctly ignored |
| 2026-08-22 | Secret-pattern scan over staged diff | No non-placeholder secrets |
| 2026-08-22 | `mvn dependency:resolve` | Spring Boot 4.1.1 resolves — exit 0 |
| 2026-08-22 | `mvn test-compile` | **BUILD SUCCESS** — 38 + 3 sources, `release 21`, 0 warnings |
| 2026-08-22 | `mvn test` | **BUILD SUCCESS** — 32 tests, 0 failures, 0 errors |
| 2026-08-22 | `mvn verify` | **BUILD FAILURE** — blocked by B-6, not by application code |
| 2026-08-22 | `docker run hello-world` | Succeeds — Docker engine healthy |
| 2026-08-22 | Testcontainers 1.21.3 → 1.21.4 | Fixed the Engine API 1.55 incompatibility (see TROUBLESHOOTING T-1) |
| 2026-08-22 | `docker pull mysql:8.4` × 11 | All failed — CDN transfer `EOF` |
| 2026-08-22 | `docker pull mysql:8.4` retry loop | **Succeeded on attempt 8** — Docker caches completed layers |
| 2026-08-22 | `mvn verify` (P2 complete) | **BUILD SUCCESS** — 45 tests, 0 failures |
| 2026-08-24 | `mvn verify` (P3 complete) | **BUILD SUCCESS** — 94 tests, 0 failures |
| 2026-08-25 | `mvn verify` (P4 complete) | **BUILD SUCCESS** — **146 tests** (72 unit + 74 integration), **0 failures, 0 errors** |

