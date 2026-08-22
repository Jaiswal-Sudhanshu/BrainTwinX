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
| P2 | Architecture & database | `[-]` IN_PROGRESS — code complete, schema verification blocked |
| P3 | Authentication & authorisation | `[ ]` NOT_STARTED |
| P4 | Patient management | `[ ]` NOT_STARTED |
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

**Status: IN_PROGRESS.** Implementation complete; schema verification **blocked on an
environment issue**, not on code.

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

### Blocked

- [!] **`mvn verify` (integration tests) — BLOCKED.** See B-6 below. `mvn test` passes;
      only the Testcontainers-backed suite cannot run.
- [!] Testcontainers MySQL integration test — migration applies from empty schema
- [!] Hibernate `ddl-auto=validate` agreement between entities and migration **asserted at
      runtime** (the code is written; the assertion has not executed)
- [!] The 9 schema-level safety-invariant tests in `SchemaMigrationIT` (forecast-with-
      insufficient-history rejection, confidence range, failed-scan-must-have-reason,
      duplicate-upload rejection, no dice/iou columns, …)

### Not yet started

- [ ] `docs/system-design/01-SYSTEM-OVERVIEW.md`
- [ ] `docs/system-design/02-HIGH-LEVEL-DESIGN.md`
- [ ] `docs/system-design/05-DATABASE-DESIGN.md`
- [x] `docs/system-design/14-ER-DIAGRAM.md` — Mermaid ER diagram, cardinality, ON DELETE and index rationale (designed, not verified)
- [ ] Maven Wrapper (`mvnw`) committed — currently depends on host Maven in `~/Downloads`
      (risk R-6)

**Exit criteria met: NO.** The schema is written but has never been applied to a real
database, so it is **not** verified. Nothing in this phase may be marked `VERIFIED`.


---

## P3 — Authentication & authorisation

- [ ] User entity + roles ADMIN / DOCTOR / RESEARCHER
- [ ] BCrypt password hashing
- [ ] JWT issue + validate
- [ ] Access + refresh flow
- [ ] `SecurityFilterChain` — deny by default
- [ ] `POST /api/v1/auth/login` · `logout` · `refresh`
- [ ] `GlobalExceptionHandler` + standard error envelope with `traceId`
- [ ] Correlation ID filter
- [ ] Audit events LOGIN / LOGOUT
- [ ] Test: unauthenticated → 401, no stack trace
- [ ] Test: wrong role → 403
- [ ] Test: expired token → 401 with distinguishable code
- [ ] Test: every mapped endpoint has an explicit authorisation rule
- [ ] Test: no password / token / secret reaches any log

---

## P4 — Patient management

- [ ] Patient entity with public-safe `patientCode`
- [ ] Create / read / update / archive (soft)
- [ ] Pagination + sorting
- [ ] Bean Validation + field-level error responses
- [ ] Duplicate `patientCode` handling
- [ ] DTO + mapper layer (no JPA entity exposed from controllers)
- [ ] Audit events PATIENT_CREATED / PATIENT_UPDATED
- [ ] Test: full CRUD
- [ ] Test: IDOR — cannot access out-of-scope patient
- [ ] Test: no PHI in URLs, logs, or error messages

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
| B-6 | **Schema verification (`mvn verify`)** | **Cannot pull the `mysql:8.4` Docker image.** 12 pull attempts failed, and the AWS ECR public mirror fails identically on a different CDN host with `httpReadSeeker: failed open: ... EOF` from Docker Hub's CDN. Docker itself works (`hello-world` runs; `testcontainers/ryuk:0.12.0` pulled successfully and its container was created), and the Testcontainers↔Docker connection defect was found and fixed. The only remaining obstacle is downloading the ~250 MB database image. | A successful `docker pull mysql:8.4` on a stable connection. Then `mvn verify` runs unchanged — no code change required. |

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

