# BrainTwinX

**AI-assisted Brain MRI Analysis and Research Platform**

> ### ⚠️ Medical disclaimer
>
> BrainTwinX provides AI-assisted image analysis for **research and decision-support
> purposes**. AI-generated results are **not a definitive medical diagnosis** and must
> not replace evaluation by a qualified healthcare professional.
>
> The platform deliberately distinguishes **AI prediction** from **clinical diagnosis**
> throughout its UI, API responses, and generated reports.

---

## Project status

🚧 **Early development — Phase 1 of 16 complete.**

This project is being built greenfield. The repository was empty at the start of work;
see [`docs/CURRENT_STATE.md`](docs/CURRENT_STATE.md) for the audited baseline and the
evidence behind that statement.

**Honest status summary:**

| Area | Status |
|---|---|
| Repository audit & scaffold | ✅ Complete |
| Architecture & database | ⬜ Not started |
| Authentication & RBAC | ⬜ Not started |
| Patient management | ⬜ Not started |
| MRI upload & validation | ⬜ Not started |
| AI service & inference | ⬜ Not started |
| Trained models | ⛔ **Blocked — no dataset available** |
| Explanation layer | ⛔ **Blocked — no LLM provider configured** |
| Reporting | ⬜ Not started |
| Frontend | ⬜ Not started |
| Docker & deployment | ⬜ Not started |

Live per-feature tracking: [`docs/TASKS.md`](docs/TASKS.md).

**Nothing in this repository is claimed to work until it has been implemented, tested,
and verified.** There are no trained models and therefore no accuracy claims. See
[`docs/ASSUMPTIONS.md`](docs/ASSUMPTIONS.md).

---

## Intended capabilities

Once complete, an authorised user will be able to:

- authenticate with role-based access (ADMIN / DOCTOR / RESEARCHER)
- create and manage patient records using public-safe identifiers
- upload and validate MRI scans
- run a deterministic, versioned preprocessing pipeline
- obtain AI-based tumour classification with genuine model confidence
- obtain tumour segmentation with mask overlay visualisation
- perform longitudinal analysis where sufficient history exists
- obtain model-based trend estimates (never presented as certainty)
- receive model-grounded explanations that cannot invent clinical findings
- generate and download professional PDF reports
- rely on audit logging of all significant actions

---

## Architecture

```
                          USER
                            │
                            ▼
                     React Frontend
                            │
                          HTTPS
                            │
                            ▼
                   Spring Boot Backend
                   ╱        │        ╲
                  ╱         │         ╲
                 ▼          ▼          ▼
             MySQL     AI Service   File Storage
                            │
                  ┌─────────┼─────────┐
                  ▼         ▼         ▼
                 CNN      U-Net     LSTM
                            │
                            ▼
                      AI Results
                            │
                            ▼
                   Explanation Layer
                            │
                            ▼
                     Report Service
```

Full design package: [`docs/system-design/`](docs/system-design/).
Decision records: [`docs/ADR/`](docs/ADR/).

---

## Technology stack

| Layer | Technology | Notes |
|---|---|---|
| Frontend | React + TypeScript (Vite) | |
| Backend | Spring Boot, Java 21 target | Compiled `--release 21` on JDK 25 |
| AI service | Python, FastAPI, PyTorch | Runtime pinned in container |
| Database | MySQL 8 | Flyway migrations |
| Storage | Local filesystem behind a `StorageService` port | Object-storage ready |
| Auth | JWT + BCrypt | Deny-by-default authorisation |
| Container | Docker + Compose | |

Rationale for each choice is recorded as an ADR rather than assumed.

---

## Prerequisites

Verified present on the development host:

| Tool | Required | Found |
|---|---|---|
| JDK | 21+ | 25.0.4 (Temurin) |
| Maven | 3.9+ | 3.9.16 — *wrapper will be committed so this is not required* |
| Node.js | 20+ | 24.16.0 |
| Python | 3.11+ | 3.14.5 |
| Docker | 24+ | 29.6.2 |
| Git | 2.x | 2.45.1 |
| MySQL | 8.x | **not installed locally** — use the Docker container |

---

## Getting started

> Setup instructions will be completed as each phase lands. The steps below reflect
> what exists **today** and will be expanded — they are not aspirational.

```bash
git clone <this-repository>
cd BrainTwinx

cp .env.example .env
# Then edit .env and set real values, in particular:
#   MYSQL_PASSWORD  and  JWT_SECRET  (generate: openssl rand -base64 48)
```

Nothing is runnable yet — the backend, frontend, and AI service are scaffolded
directories at this stage. Build and run instructions arrive with Phase 2 (backend),
Phase 6 (AI service), and Phase 12 (frontend).

---

## Environment variables

All configuration is supplied via environment variables. See
[`.env.example`](.env.example) for the complete, documented list.

**No secrets are committed to this repository.** `.env` is git-ignored;
`.env.example` contains non-functional placeholders only.

Safety-relevant settings worth highlighting:

| Variable | Purpose |
|---|---|
| `AI_ALLOW_STUB_INFERENCE` | Must stay `false` outside local development. When enabled, output is synthetic and tagged non-clinical. The production profile refuses to start with it on. |
| `EXPLANATION_ENABLED` | Explanation layer fails closed when unconfigured. |
| `LONGITUDINAL_MIN_OBSERVATIONS` | Below this, the system returns `INSUFFICIENT_HISTORY` rather than a fabricated trend. |
| `LOG_SQL` | Must stay `false` in production. |

---

## AI model setup

**There are currently no trained models, and no dataset is available.**

This is a deliberate, documented gap rather than an oversight. The platform ships:

- real preprocessing (deterministic and versioned)
- real model-loading interfaces with checksum and input-shape verification
- real inference orchestration, persistence, and version tracking
- a genuinely runnable training and evaluation harness

What it does **not** ship is trained weights or any accuracy figure. With weights
absent, the AI service reports **NOT READY** and analysis endpoints return a typed
error — they never fabricate a prediction.

To supply models and data, see [`docs/DATASET_SETUP.md`](docs/DATASET_SETUP.md)
*(written in Phase 7)*.

---

## Testing

Test commands are documented per phase as suites are added. Strategy:
[`docs/TESTING_STRATEGY.md`](docs/TESTING_STRATEGY.md) *(Phase 13)*.

---

## Documentation

| Document | Contents |
|---|---|
| [`docs/CURRENT_STATE.md`](docs/CURRENT_STATE.md) | Audited baseline with evidence |
| [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) | Phased plan, dependencies, exit criteria |
| [`docs/TASKS.md`](docs/TASKS.md) | Live per-feature status |
| [`docs/ASSUMPTIONS.md`](docs/ASSUMPTIONS.md) | Decisions taken under ambiguity |
| `docs/system-design/` | 16-part design package *(Phases 2–16)* |
| `docs/ADR/` | Architecture decision records |
| `docs/SECURITY.md` | Threat model and mitigations |
| `docs/LIMITATIONS.md` | What the system cannot do |

---

## Known limitations

Recorded honestly and up front:

1. **No trained models.** No dataset is available; no accuracy is claimed.
2. **No explanation provider configured.** The layer fails closed.
3. **2-D images only** (PNG/JPEG). DICOM and NIfTI are out of scope —
   DICOM carries embedded PHI requiring a de-identification design.
4. **Not clinically validated.** No regulatory clearance of any kind.
5. **Tumour area is reported in preprocessed pixels**, not mm² — physical spacing
   metadata is unavailable for 2-D inputs, and deriving mm² from an assumed spacing
   would be a fabricated measurement.

Reasoning for each: [`docs/ASSUMPTIONS.md`](docs/ASSUMPTIONS.md).

---

## Licence

Not yet specified.
