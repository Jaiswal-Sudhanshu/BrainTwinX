# BrainTwinX — Release Notes

## Release `v0.1.0-alpha` (Foundational Release)

**Release Date:** September 2026  
**Tag:** `v0.1.0-alpha`  
**Git Commit:** Initial platform foundation  
**Status:** Alpha / Core Platform & AI Service Ready  

---

### 🌟 Executive Summary

`v0.1.0-alpha` marks the foundational release of **BrainTwinX**, an AI-assisted brain MRI analysis and research platform built for neuro-oncology clinical decision support. This release establishes the core microservice architecture, database governance, clinical role-based security, patient management lifecycle, secure scan ingestion with byte-level validation, and the PyTorch-based AI inference engine.

---

### 🚀 Key Highlights & What's Included

#### 1. Core Architecture & Database Governance (Phases 1 & 2)
- **Spring Boot 4.1.1** backend on **Java 21 LTS** (`--release 21`).
- **MySQL 8.4 LTS** schema managed through versioned **Flyway** migrations (`V1__baseline.sql`, `V2__seed_model_versions.sql`).
- Enforced 14 database check constraints and safety invariants guaranteeing data integrity.
- Integration tests verified against containerised MySQL via **Testcontainers**.

#### 2. Authentication & Role-Based Access Control (Phase 3)
- Stateless authentication using **JSON Web Tokens (JWT)** and **BCrypt** password hashing.
- Three distinct operational roles:
  - `ADMIN`: User administration, system settings, and global audit log inspection.
  - `DOCTOR`: Patient record creation, scan uploads, and AI analysis requests.
  - `RESEARCHER`: Anonymised data querying and research model evaluation.
- Deny-by-default security filter chain rejecting unauthorised and unmapped requests.

#### 3. Patient Lifecycle Management (Phase 4)
- Public-safe patient codes (`PAT-XXXXXX`) avoiding exposed database sequential IDs.
- Zero Protected Health Information (PHI) leaked into URLs, query parameters, error bodies, or system logs.
- Full CRUD support with soft deletion / archival preserving clinical audit history.

#### 4. MRI Upload & Image Defense Pipeline (Phase 5)
- Decoupled `StorageService` port allowing seamless switching between local filesystem and cloud object stores (S3/GCS).
- Strict multi-layer image validation:
  - Magic-byte verification (PNG/JPEG signatures) preventing extension spoofing.
  - Decompression bomb defence with dimension caps (up to 4096×4096) and byte-size limits.
  - Byte-stream decode verification ensuring images are not corrupted or truncated.
  - Server-side UUID file renaming preventing directory traversal attacks.
  - SHA-256 hash tracking and patient-scoped duplicate detection.

#### 5. AI Service Microservice & Preprocessing (Phase 6)
- **FastAPI** Python service with asynchronous execution and Pydantic v2 schemas.
- Deterministic preprocessing pipeline (`v1.0.0`): converts 2D MRI scans to single-channel grayscale, resizes to 224×224 via bilinear interpolation, normalises pixel intensity to `[0.0, 1.0]`, and outputs PyTorch tensors.
- 4-stage convolutional neural network (**PyTorch CNN**) for tumour classification (Glioma, Meningioma, Pituitary, No Tumour).
- Model Registry with SHA-256 weight verification on boot.
- Lifespan model cache loaded once per worker.
- Spring Boot `WebClient` integration with timeouts, retries, and typed failure mapping.
- Local stub inference mode (`AI_ALLOW_STUB_INFERENCE=true`) explicitly tagging synthetic predictions as non-clinical for offline testing.

---

### 🧪 Verification & Test Metrics

- **Backend Test Suite:** **201 passing tests** (111 unit tests, 90 integration tests with Testcontainers), 0 failures, 0 errors.
- **AI Service Test Suite:** **7 passing pytest tests** covering auth, schema validation, deterministic preprocessing, and liveness/readiness probes.
- **Database Schema:** 11 relational tables with foreign keys and check constraints verified under real MySQL.

---

### 📦 System Requirements

| Tool | Minimum Version | Tested Version |
|---|---|---|
| OpenJDK | 21 LTS | 25.0.4 Temurin (`--release 21`) |
| Python | 3.11+ | 3.14.5 |
| Node.js | 20+ | 24.16.0 |
| Docker | 24+ | 29.6.2 |
| MySQL | 8.0+ | 8.4.0 LTS |

---

### 📋 API Endpoints Released in `v0.1.0-alpha`

| Group | Method | Path | Summary |
|---|---|---|---|
| **Auth** | `POST` | `/api/v1/auth/register` | Register new user account |
| **Auth** | `POST` | `/api/v1/auth/login` | Login and receive JWT access & refresh tokens |
| **Auth** | `POST` | `/api/v1/auth/refresh` | Exchange refresh token for new access token |
| **Patients** | `POST` | `/api/v1/patients` | Create patient profile |
| **Patients** | `GET` | `/api/v1/patients` | List paginated patient records |
| **Patients** | `GET` | `/api/v1/patients/{patientCode}` | Get patient details |
| **Patients** | `PUT` | `/api/v1/patients/{patientCode}` | Update patient profile |
| **Patients** | `DELETE` | `/api/v1/patients/{patientCode}` | Soft-delete patient profile |
| **Scans** | `POST` | `/api/v1/patients/{patientCode}/scans` | Upload and validate MRI scan file |
| **Scans** | `GET` | `/api/v1/patients/{patientCode}/scans` | List patient scan records |
| **Scans** | `GET` | `/api/v1/scans/{scanId}` | Get scan metadata |
| **Scans** | `GET` | `/api/v1/scans/{scanId}/file` | Download stored scan image |
| **Inference** | `POST` | `/api/v1/scans/{scanId}/classify` | Trigger tumour classification analysis |
| **Inference** | `GET` | `/api/v1/analyses/{jobId}` | Get classification results and status |
| **AI Internal** | `GET` | `/internal/ai/v1/health` | Liveness health check probe |
| **AI Internal** | `GET` | `/internal/ai/v1/ready` | Readiness check (validates loaded models) |
| **AI Internal** | `POST` | `/internal/ai/v1/classify` | Execute CNN tumour classification |

---

### 🔮 Roadmap & Upcoming Releases

- **Phase 7 (v0.2.0)**: CNN Model Training harness on public BraTS datasets & evaluation pipeline.
- **Phase 8 (v0.3.0)**: U-Net tumour segmentation with mask overlays and pixel area computation.
- **Phase 9 (v0.4.0)**: Longitudinal tracking & LSTM trend analysis across multiple scans.
- **Phase 10 (v0.5.0)**: Guard-railed LLM explanation layer for clinical assistance.
- **Phase 11 (v0.6.0)**: PDF diagnostic report generation and download.
- **Phase 12 (v0.7.0)**: React + TypeScript (Vite) frontend web application.
- **Phase 13–16 (v1.0.0)**: End-to-end testing, security pen-testing, production Docker Compose, and final release.
