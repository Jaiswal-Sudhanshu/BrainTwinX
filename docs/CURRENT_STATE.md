# BrainTwinX — Current State

**Audit date:** 2026-08-22 · **Progress updated:** 2026-08-25
**Audited by:** Engineering owner (initial repository audit)
**Audit scope:** `C:\Users\jsudh\projects\BrainTwinx` and the surrounding host filesystem
**Document status:** AUTHORITATIVE for the pre-implementation baseline

---

## 1. Executive summary

At the time of audit, the BrainTwinX repository was **completely empty**. There was no
frontend, no backend, no AI service, no database schema, no configuration, no tests,
no Docker assets, and no documentation. No Git repository existed.

BrainTwinX is therefore a **greenfield project**, not an inherited codebase. This was
confirmed with the project owner before any code was written.

This document exists to record the verified baseline so that future contributors can
distinguish *"this was never built"* from *"this was built and later removed"*.

---

## 2. Audit method and evidence

The emptiness of the directory was not accepted from a single directory listing.
The following independent checks were performed.

| # | Check | Command / location | Result |
|---|---|---|---|
| 1 | Directory contents | `ls -la` in project root | Only `.` and `..` |
| 2 | Recursive item count incl. hidden | `Get-ChildItem -Force -Recurse \| Measure-Object` | **0 items** |
| 3 | Git working tree | `git rev-parse --is-inside-work-tree` | Exit 128 — not a repository |
| 4 | Name search across user profile | `C:\Users\jsudh` recursive, depth 3, filter `*rain*win*` | Only Claude session-metadata folders; no source |
| 5 | Previously referenced location | `D:\BrainTwinx` (from a stale session folder name) | Path does not exist |
| 6 | Related directory | `D:\mri` | Exists but empty |
| 7 | Domain-named directories | `D:\` recursive depth 2, matching `brain\|tumor\|mri\|twin` | Only the empty `D:\mri` |
| 8 | Archive / alternate IDE roots | `Downloads`, `Documents`, `OneDrive`, `PycharmProjects`, `StudioProjects` | No BrainTwinX source or archive |
| 9 | Prior assistant sessions | 3 session transcripts read in full | No code was ever authored; content was `/resume` troubleshooting and a bare greeting |

**Conclusion:** no prior BrainTwinX implementation exists on this host, in any form,
including archives.

---

## 3. Existing architecture

**None.** No architectural artefacts of any kind were present.

For the architecture the project is being built *towards*, see
[`docs/system-design/02-HIGH-LEVEL-DESIGN.md`](./system-design/02-HIGH-LEVEL-DESIGN.md).

---

## 4. Existing technologies

No project-declared technologies existed (no `pom.xml`, `build.gradle`, `package.json`,
`requirements.txt`, or `pyproject.toml`).

The **host toolchain** was inventoried, since it constrains implementation choices:

| Tool | Version found | Notes |
|---|---|---|
| JDK (Temurin) | 25.0.4 | `javac 25.0.4`; compiles with `--release 21` for a stable Spring Boot target |
| Apache Maven | 3.9.16 | Running from `C:\Users\jsudh\Downloads\apache-maven-3.9.16-bin` — not installed to a stable location |
| Node.js | 24.16.0 | |
| npm | 12.0.2 | |
| Python | 3.14.5 (win-amd64) | |
| pip | 26.2 | |
| PyTorch wheel availability | 2.13.0 for cp314 | **Verified** — local ML dev is viable on Python 3.14 |
| NumPy wheel availability | 2.5.2 | Verified |
| Docker | 29.6.2 | |
| Git | 2.45.1.windows.1 | |
| MySQL client | **Not on PATH** | Installer present in `Downloads`; server state unverified. Containerised MySQL is the supported path. |

---

## 5. Existing modules

None.

---

## 6. Existing database

No schema, no migrations, no seed data, no connection configuration.

---

## 7. Existing APIs

None. No controllers, no route definitions, no OpenAPI specification.

---

## 8. Existing AI pipeline

None. Specifically absent:

- no preprocessing code
- no model definitions
- no trained weights or checkpoints
- no dataset
- no training scripts
- no evaluation harness
- no model registry or version metadata

---

## 9. Existing authentication

None. No user model, no password hashing, no JWT handling, no authorisation rules.

---

## 10. Existing deployment assets

None. No Dockerfiles, no Compose file, no CI configuration, no infrastructure code.

---

## 11. Existing tests

None. No test source trees, no test configuration, no fixtures.

---

## 12. Known bugs

**Not applicable.** There is no executable code, therefore no defects.

This entry is deliberately explicit: an empty "Known bugs" section in a greenfield
audit must not be misread as "the code was reviewed and found correct".

---

## 13. Missing functionality

Every requirement in the project brief is unimplemented. Tracked in
[`TASKS.md`](./TASKS.md); sequenced in [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md).

Summarised by area:

- **Authentication & authorisation** — login, logout, refresh, password hashing, JWT, RBAC (ADMIN / DOCTOR / RESEARCHER), route and endpoint protection
- **Patient management** — CRUD, archival, public-safe identifiers, validation, history
- **Scan management** — upload, file validation, safe storage, hashing, status lifecycle
- **Preprocessing** — deterministic, versioned pipeline
- **Classification** — CNN inference interface and plumbing
- **Segmentation** — U-Net inference interface, mask output, area metrics
- **Longitudinal analysis** — history sufficiency checks, LSTM trend estimation
- **Explanation layer** — structured-input-only, guard-railed LLM explanation
- **Reporting** — PDF generation, storage, download
- **Audit logging** — the event set in brief §27
- **Observability** — structured logs, correlation IDs, metrics, health/readiness
- **Security controls** — the full set in brief §25 and §54
- **Testing** — backend, frontend, AI, and end-to-end
- **Deployment** — Dockerfiles, Compose, environment configuration
- **Documentation** — README, `docs/`, and the 16-part system-design package

---

## 14. Technical debt

**Zero inherited debt.** This is the single advantage of the greenfield state: there is
no legacy structure, no duplicated code, no dead code, and no TODO/FIXME backlog to
unwind. Architectural quality is therefore entirely a function of decisions made from
this point forward.

---

## 15. Security posture

There are currently **no vulnerabilities, because there is no attack surface.**

This is a meaningful distinction. Every control listed in brief §25 and §54 is a
*build* task, not a *remediation* task. They are enumerated with their mitigations in
[`SECURITY.md`](./SECURITY.md) and
[`system-design/08-SECURITY-DESIGN.md`](./system-design/08-SECURITY-DESIGN.md).

No secrets were found committed, since nothing was committed.

---

## 16. Risks carried into implementation

| ID | Risk | Impact | Mitigation |
|---|---|---|---|
| R-1 | **No MRI dataset available.** Confirmed with the owner. | Models cannot be trained; no accuracy or metric claims are possible. | Ship real interfaces, real preprocessing, and a runnable training/eval harness. Hard-gate stub inference behind explicit config; never present it as clinical output. Document required data in `DATASET_SETUP.md`. See [`ASSUMPTIONS.md`](./ASSUMPTIONS.md) A-1. |
| R-2 | **Medical-safety misrepresentation.** An AI decision-support tool can be mistaken for a diagnostic device. | Patient harm; regulatory exposure. | Enforced qualified language, mandatory disclaimer in UI/API/report, strict separation of "AI prediction" from "clinical diagnosis". Brief §44–45. |
| R-3 | **No LLM provider configured** for the explanation layer. | Explanations unavailable. | Provider-agnostic interface that fails closed and returns a typed error when unconfigured. |
| R-4 | **Python 3.14 is very new.** Some ML/CV libraries may lag. | Local dev friction. | PyTorch 2.13.0 and NumPy 2.5.2 cp314 wheels verified present. Docker image pins the runtime so CI and production do not depend on host Python. |
| R-5 | **Java 25 host vs. Spring Boot supported range.** | Build failure. | Compile with `--release 21` against a Spring Boot version whose Java support is verified by an actual build, not assumed. |
| R-6 | **Maven runs from `Downloads`.** | Fragile, non-reproducible builds. | Commit the Maven Wrapper (`mvnw`) so the build is self-contained. |
| R-7 | **MySQL not installed locally.** | Cannot run the app outside Docker. | Containerised MySQL is the supported local path; documented in `DEPLOYMENT.md` and `TROUBLESHOOTING.md`. |
| R-8 | **Scope is very large** relative to a single delivery pass. | Partial features presented as complete. | Strict per-feature status tracking (`NOT_STARTED` → `VERIFIED`) in `TASKS.md`. No feature is called done before implementation *and* tests *and* integration. Brief §46. |
| R-9 | **Patient data sensitivity.** | Privacy breach. | Data minimisation, public-safe identifiers, no PHI in URLs/logs/errors, audit logging. Brief §26. |

---

## 17. Progress since baseline

The sections above are **frozen as the audited starting point** (2026-08-22). This section
records verified progress against it, so the document stays useful without rewriting history.

| Phase | Status | Verification |
|---|---|---|
| P1 Audit & scaffold | ✅ COMPLETE | 9 audit checks; `.gitignore` proven to exclude `.env`, PHI, weights |
| P2 Architecture & database | ✅ COMPLETE | `mvn verify` — 45 tests; 11 tables; 14 safety invariants exercised |
| P3 Authentication & RBAC | ✅ COMPLETE | `mvn verify` — 94 tests; deny-by-default proven on 8 paths |
| P4 Patient management | ✅ COMPLETE | `mvn verify` — **146 tests** (72 unit + 74 integration), 0 failures, 0 errors |
| P5–P16 | ⬜ NOT STARTED | — |

**Risks from §16 now closed:**

| ID | Was | Now |
|---|---|---|
| R-4 | Python 3.14 may lack ML wheels | Closed — `torch 2.13.0` cp314 verified available |
| R-5 | Java 25 vs Spring Boot support | Closed — Spring Boot 4.1.1 builds and runs; `--release 21` |
| R-6 | Maven runs from `~/Downloads` | Closed — wrapper committed and verified with no host Maven on `PATH` |
| R-7 | MySQL not installed locally | Closed — containerised MySQL 8.4 is the supported path and is in use |

**Risks still open:** R-1 (no dataset), R-2 (medical-safety misrepresentation — mitigated by
14 schema-enforced invariants but permanently live), R-3 (no LLM provider), R-8 (scope vs.
single pass), R-9 (patient data sensitivity — mitigated in P4 by scope enforcement, PHI-free
error responses, and audit logging, all under test).

**Toolchain findings added since baseline** (each cost real debugging time; see
[`TROUBLESHOOTING.md`](./TROUBLESHOOTING.md)):

- Spring Boot 4 split `spring-boot-autoconfigure` into per-technology modules — `flyway-core`
  alone does not activate Flyway (T-7)
- Spring Boot 4 ships **Jackson 3**: packages are `tools.jackson.*`, not
  `com.fasterxml.jackson.*` (T-9)
- Testcontainers 1.21.3 cannot negotiate Docker Engine API 1.55; 1.21.4 can (T-1)
- A static `@Container` on a shared abstract base is stopped after the first subclass (T-10)
- MySQL error 3819 (CHECK violated) is absent from Spring's data-integrity code list, so it
  surfaces as `UncategorizedSQLException` (T-8)

---

## 18. What this document will become

This file records the **baseline** plus the progress table above. Sections 1–16 are
intentionally frozen as a historical record of the starting point.

Live state is tracked in:

- [`TASKS.md`](./TASKS.md) — per-feature status
- [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md) — sequencing
- [`ASSUMPTIONS.md`](./ASSUMPTIONS.md) — decisions made under ambiguity
- [`LIMITATIONS.md`](./LIMITATIONS.md) — what the system cannot do

---

## Medical disclaimer

BrainTwinX provides AI-assisted image analysis for research and decision-support
purposes. AI-generated results are not a definitive medical diagnosis and should not
replace evaluation by a qualified healthcare professional.
