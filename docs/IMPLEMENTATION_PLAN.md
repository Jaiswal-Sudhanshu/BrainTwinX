# BrainTwinX — Implementation Plan

**Version:** 1.0
**Date:** 2026-08-22
**Baseline:** [`CURRENT_STATE.md`](./CURRENT_STATE.md) — greenfield, empty repository
**Sequencing authority:** project brief §40, refined below with dependencies and exit criteria

---

## 1. Planning principles

1. **Vertical before horizontal.** Get one thin path working end to end (login → patient →
   upload → analyse → result → report) before broadening any single layer. A platform that
   is 100% complete in three layers and 0% in the fourth demonstrates nothing.
2. **No phase is left in a critical-failure state.** Brief §40. A phase exits only when its
   exit criteria are met and its tests pass.
3. **Honesty over apparent completeness.** Brief §46. Where a dependency is genuinely
   missing (dataset, LLM key), ship the correct interface, fail closed, and document the
   gap. Never simulate the missing part and present it as working.
4. **Design docs track implementation, not precede it by months.** The §61 system-design
   package is written alongside the phase that implements each area, so documentation and
   code cannot drift. HLD/LLD skeletons land in Phase 2; each subsequent phase fills in
   its own section.
5. **Minimal dependencies.** Brief §60. Before adding a library, check whether an existing
   one already solves the problem. Every added dependency needs a reason.
6. **Verify, don't assume.** Version compatibility (Spring Boot ↔ Java 25, torch ↔ Python
   3.14) is settled by running a build, not by recollection.

---

## 2. Critical path

```
P1 Audit & scaffold
      │
P2 Architecture + database schema ─────────────┐
      │                                        │ (schema is a hard dependency
P3 Authentication + RBAC                       │  for every persistence phase)
      │                                        │
P4 Patient management ◄────────────────────────┘
      │
P5 MRI upload + validation + storage
      │
P6 AI service skeleton (lifecycle, health, readiness, preprocessing)
      │
      ├── P7 CNN classification ──┐
      ├── P8 U-Net segmentation ──┤
      └── P9 Longitudinal / LSTM ─┤
                                  │
P10 Explanation layer ◄───────────┘  (consumes structured AI results only)
      │
P11 Report generation
      │
P12 Frontend integration
      │
P13 Testing (broadened; unit tests are written *within* each phase, not deferred here)
      │
P14 Security hardening + threat-model verification
      │
P15 Docker / deployment
      │
P16 Documentation completion + final acceptance sweep
```

Phases 7, 8 and 9 are parallelisable once P6 lands, because each depends only on the
preprocessing contract and the model-loading lifecycle — not on one another.

---

## 3. Phase detail

Each phase below states: **goal**, **deliverables**, **exit criteria**, and **risks**.

### P1 — Repository audit & scaffold  *(this phase)*

| | |
|---|---|
| **Goal** | Establish a verified baseline and a professional repository skeleton. |
| **Deliverables** | Verified audit; `CURRENT_STATE.md`, `IMPLEMENTATION_PLAN.md`, `TASKS.md`, `ASSUMPTIONS.md`; directory scaffold; `.gitignore`; `.env.example`; Git repository initialised. |
| **Exit criteria** | Audit evidence recorded. Scaffold matches brief §5. No secrets tracked. `.gitignore` covers build output, virtualenvs, `node_modules`, `.env`, model weights, and uploaded scans. |
| **Risks** | Baseline mis-recorded as "existing project" — mitigated by evidence table in `CURRENT_STATE.md`. |

### P2 — Architecture & database

| | |
|---|---|
| **Goal** | Fix the contracts everything else builds on: the schema and the module boundaries. |
| **Deliverables** | HLD + LLD skeletons; ER diagram; Flyway migration `V1__baseline.sql` covering `users`, `patients`, `scans`, `predictions`, `segmentation_results`, `growth_predictions`, `reports`, `audit_logs`, `model_versions`; JPA entities; repositories; Spring Boot project that builds and starts; ADR-001…005. |
| **Exit criteria** | `mvn verify` passes. Application context loads against a Testcontainers MySQL. Migration applies cleanly from empty schema. Every FK, unique constraint, and index is deliberate and documented. Scan status enum and its legal transitions are defined in one place. |
| **Risks** | Schema churn later is expensive — so status lifecycles (brief §52) and model-version tracking (§22) are designed in now, not retrofitted. Spring Boot ↔ Java 25 compatibility verified by building, not assuming. |

### P3 — Authentication & authorisation

| | |
|---|---|
| **Goal** | Make every subsequent endpoint protectable by default. |
| **Deliverables** | BCrypt password hashing; JWT issue/validate; access + refresh flow; `SecurityFilterChain` with deny-by-default; roles ADMIN / DOCTOR / RESEARCHER; `/api/v1/auth/{login,logout,refresh}`; centralised `GlobalExceptionHandler`; standard error envelope with `traceId`; audit events LOGIN / LOGOUT. |
| **Exit criteria** | Unauthenticated request to a protected endpoint → 401 with the standard envelope, no stack trace. Wrong-role request → 403. Expired token → 401 with a distinguishable code. Tests assert all three. No password, token, or secret appears in any log. |
| **Risks** | Deny-by-default must be the *default*, so a forgotten annotation fails closed rather than open. Verified by a test that enumerates mapped endpoints and asserts each has an explicit rule. |

### P4 — Patient management

| | |
|---|---|
| **Goal** | First full CRUD slice through the architecture, establishing the DTO/mapper/service/repository pattern. |
| **Deliverables** | Patient entity with public-safe `patientCode` (never a sequential DB id in URLs); create/read/update/archive; pagination; Bean Validation; duplicate-code handling; audit events PATIENT_CREATED / PATIENT_UPDATED. |
| **Exit criteria** | Full CRUD tested at controller and service level. Archive is soft, not destructive. Validation rejects over-length, malformed dates, and unknown enum values with field-level errors. IDOR test: a user cannot read a patient outside their permitted scope. |
| **Risks** | PHI leakage via URLs, logs, or error messages (brief §26) — asserted against in tests. |

### P5 — MRI upload, validation & storage

| | |
|---|---|
| **Goal** | Treat upload as the security boundary it is. |
| **Deliverables** | Multipart upload; extension + declared-MIME + **magic-byte signature** validation; size cap; readability and corruption check; server-generated storage names; SHA-256 content hash; scan record with status `UPLOADED`; local filesystem storage behind a `StorageService` port; audit event SCAN_UPLOADED. |
| **Exit criteria** | Rejects: wrong extension, spoofed MIME, mismatched magic bytes, zero-byte file, oversized file, truncated/corrupt image, and path-traversal filenames (`../`, absolute paths, NUL bytes, Windows reserved names). Each has a test. No user-supplied string ever reaches a filesystem path. Hash recorded and duplicate uploads detectable. |
| **Risks** | Decompression bombs and pixel-dimension bombs — dimension and decompressed-size limits enforced before full decode. |

### P6 — AI service foundation

| | |
|---|---|
| **Goal** | A correct, observable inference host — before any model exists. |
| **Deliverables** | FastAPI app; config validation at startup; model registry abstraction; **models loaded once per worker** (brief §37); `/health` (liveness) and `/ready` (readiness, false unless models loaded and shape-validated); deterministic versioned preprocessing pipeline (`preprocessingVersion`); Pydantic request/response schemas; internal-only network exposure; backend→AI client with timeout and typed failure mapping to `AI_SERVICE_UNAVAILABLE`. |
| **Exit criteria** | Service reports NOT READY when weights are absent — and the backend degrades predictably instead of hanging or 500-ing. Preprocessing is byte-deterministic for a fixed input and version, proven by test. Schema-validation tests cover malformed and hostile payloads. |
| **Risks** | The temptation to make `/ready` return true so the demo works. Explicitly forbidden: readiness must reflect reality. |

### P7 — CNN classification

| | |
|---|---|
| **Goal** | Real classification plumbing with an honest gap where the trained model belongs. |
| **Deliverables** | `Classifier` interface; PyTorch loader with checksum verification and input-shape assertion; `PredictionResult` carrying `predictedClass`, real `confidence`, full `probabilities`, `modelName`, `modelVersion`, `preprocessingVersion`, `inferenceTimestamp`; persistence within a transaction; idempotency key so a repeated `POST /analyze` does not duplicate work (brief §21); async job + status polling (brief §20); training + evaluation scripts that are runnable once data exists; `DATASET_SETUP.md`. |
| **Exit criteria** | Confidence values originate from actual model output — never hardcoded, never synthesised. With no weights present the endpoint returns a typed, documented error; it does **not** invent a prediction. Any development stub is gated behind an explicit non-default config flag, is labelled non-clinical in every response, and is proven by test to be off by default. Status transitions follow the legal state machine only. |
| **Risks** | This is the single highest-integrity-risk phase in the project. The failure mode — fabricated predictions presented as real — is the exact thing brief §11 and §46 prohibit. |

### P8 — U-Net segmentation

| | |
|---|---|
| **Goal** | Mask generation with metrics that are only claimed when they are meaningful. |
| **Deliverables** | `Segmenter` interface; mask persisted as an artefact (not a DB blob); `SegmentationResult` with `tumorDetected`, `tumorArea`, mask dimensions, bounding box, model identity; Dice/IoU computed **only** in the evaluation harness where ground truth exists. |
| **Exit criteria** | No Dice or IoU value is attached to a production inference (brief §12). Original scan bytes are never mutated. Area units are documented, not left as a bare pixel count of ambiguous meaning. |
| **Risks** | Reporting an evaluation metric as if it were a per-inference confidence — guarded by keeping the two code paths separate and asserting the production response schema cannot carry them. |

### P9 — Longitudinal analysis & LSTM forecasting

| | |
|---|---|
| **Goal** | Trend estimation that refuses to run on insufficient data. |
| **Deliverables** | History assembly per patient; minimum-observation and minimum-time-span policy; `INSUFFICIENT_HISTORY` typed response; LSTM forecaster interface; output labelled `MODEL-BASED TREND ESTIMATE`. |
| **Exit criteria** | With fewer than the configured minimum valid observations, the system returns `INSUFFICIENT_HISTORY` and **fabricates nothing** (brief §13). Test covers 0, 1, and *n−1* observations. No wording implies certainty about future growth. |
| **Risks** | Silent extrapolation from two points. Prevented by making the sufficiency check a precondition, not a warning. |

### P10 — Explanation layer

| | |
|---|---|
| **Goal** | Grounded natural-language explanation that cannot hallucinate clinical findings. |
| **Deliverables** | Provider-agnostic `ExplanationProvider` port; input restricted to an allow-listed structured payload (class, confidence, segmentation summary, model versions, trend status) — **the LLM never receives the image**; system prompt encoding the ten prohibitions in brief §14; post-generation validator rejecting invented measurements, symptoms, history, certainty claims, and unsupported recommendations; fail-closed when unconfigured. |
| **Exit criteria** | With no provider configured, the feature returns a typed error and the rest of the pipeline still completes. Validator has tests for each prohibition class. Explanation is persisted only after passing validation. |
| **Risks** | Prompt injection via free-text fields reaching the prompt — mitigated by the allow-list, which admits typed values only, never arbitrary user text. |

### P11 — Report generation

| | |
|---|---|
| **Goal** | A professional PDF that never overstates what the system knows. |
| **Deliverables** | PDF renderer; all eleven sections from brief §15; mandatory disclaimer; storage + `GET /reports/{id}/download`; audit events REPORT_GENERATED / REPORT_ACCESSED. |
| **Exit criteria** | Report renders with sections correctly marked *unavailable* rather than blank or invented when a stage produced no result. Disclaimer present unconditionally. Model and preprocessing versions printed. Download is authorisation-checked (IDOR test). |
| **Risks** | Empty sections reading as negative findings. Addressed by explicit "Not available" text. |

### P12 — Frontend integration

| | |
|---|---|
| **Goal** | A serious clinical/research UI, not a marketing page. |
| **Deliverables** | All routes from brief §6; protected routing; role-based UI; loading / skeleton / empty / error / success states; confirmation dialogs; form validation mirroring backend rules; MRI viewer with segmentation overlay, opacity, zoom, pan, toggle; status polling; formal language per brief §44; persistent disclaimer. |
| **Exit criteria** | No fake or placeholder patient data on any production path. Absent/processing/failed analyses render the exact copy specified in brief §34. Build and lint clean. Accessibility: keyboard navigable, labelled controls, sufficient contrast. |
| **Risks** | Informal AI phrasing creeping into UI copy — a lint-style review pass against §44 before exit. |

### P13 — Testing

Unit and integration tests are written **inside** each phase. This phase broadens
coverage rather than introducing testing late: end-to-end journey, failure-injection
(DB down, AI service down, storage failure, expired JWT), and `TESTING_STRATEGY.md`.

**Exit criteria:** the full E2E journey in brief §29 passes; every failure scenario in
brief §39 has a test asserting a *predictable* end state.

### P14 — Security hardening

Rate limiting, security headers, CORS restriction to known origins, dependency scan,
secret-scan of history, and verification of every mitigation claimed in `SECURITY.md`
against an actual test or configuration line.

**Exit criteria:** each threat in brief §54 maps to a named mitigation *and* the evidence
for it. No claimed control is undemonstrated.

### P15 — Docker & deployment

Dockerfiles for frontend, backend, ai-service; Compose for local dev; MySQL container;
env-var configuration with no committed secrets; healthcheck-gated startup ordering.

**Exit criteria:** `docker compose up` produces a working stack from a clean checkout on
a machine with no local MySQL. Documented in `DEPLOYMENT.md`.

### P16 — Documentation & final acceptance

Complete `README.md`, the `docs/` set, the 16-part `docs/system-design/` package, ADRs,
`LIMITATIONS.md`, `TROUBLESHOOTING.md`; then walk brief §59 item by item and record the
result of each honestly.

**Exit criteria:** documentation and implementation do not contradict each other; every
§59 checkbox is marked with its verified status, including any that are not met.

---

## 4. Priority ordering rationale

Ordering is driven by **dependency and risk**, not by visibility:

1. **Schema first** (P2) because retrofitting status lifecycles, model-version columns, and
   audit tables after features exist forces rewrites across every layer.
2. **Auth before any resource** (P3) so no endpoint is ever built unprotected and secured
   later — the sequence that produces most real-world access-control defects.
3. **Upload validation before inference** (P5 before P7) because the file boundary is the
   highest-severity untrusted input in the system.
4. **AI foundation before models** (P6 before P7–P9) so lifecycle, readiness, and
   preprocessing determinism are correct while they are still cheap to change.
5. **Explanation after all model outputs** (P10) because it must consume structured results
   and must never be positioned to inspect the image itself.
6. **Security hardening as an explicit phase** (P14) *in addition to* secure defaults in
   every phase — the phase verifies claims rather than introducing security late.

---

## 5. Definition of done (per feature)

A feature advances through the brief §46 ladder. It may be recorded as `VERIFIED` only when
**all** of the following hold:

- [ ] Implemented against a defined interface
- [ ] Unit tested
- [ ] Integration tested where it crosses a boundary
- [ ] Failure paths tested, not only the happy path
- [ ] Authorisation asserted where applicable
- [ ] Documented, with the docs matching the code
- [ ] Manually exercised where behaviour is visual or interactive

Status is tracked per feature in [`TASKS.md`](./TASKS.md).

---

## 6. Explicit non-goals

Stated so they are not mistaken for oversights:

| Non-goal | Reason |
|---|---|
| Clinical validation or regulatory clearance | Out of scope for a research/decision-support platform; would require a clinical study and a quality-management system. |
| Claiming trained-model accuracy | No dataset available (`ASSUMPTIONS.md` A-1). Metrics are reported only from a real evaluation run. |
| DICOM/PACS integration, HL7/FHIR | Not in the brief. Noted as future work in `LIMITATIONS.md`. |
| Kafka / Redis / microservice decomposition | Brief §20 and §55 — not justified at current scale. Revisit only with a documented trigger. |
| Multi-tenancy | Not in the brief. |
| Pushing to a remote | Not requested. Repository stays local. |

---

## Medical disclaimer

BrainTwinX provides AI-assisted image analysis for research and decision-support
purposes. AI-generated results are not a definitive medical diagnosis and should not
replace evaluation by a qualified healthcare professional.
