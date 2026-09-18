# BrainTwinX

<div align="center">

# 🧠 BrainTwinX
### AI-Assisted Brain MRI Analysis & Research Platform

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)]()
[![Java](https://img.shields.io/badge/Java-21%20LTS-orange.svg?logo=openjdk)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg?logo=springboot)]()
[![FastAPI](https://img.shields.io/badge/FastAPI-0.115+-009688.svg?logo=fastapi)]()
[![PyTorch](https://img.shields.io/badge/PyTorch-2.13+-EE4C2C.svg?logo=pytorch)]()
[![MySQL](https://img.shields.io/badge/MySQL-8.4%20LTS-4479A1.svg?logo=mysql)]()
[![Docker](https://img.shields.io/badge/Docker-Enabled-2496ED.svg?logo=docker)]()
[![Tests](https://img.shields.io/badge/Tests-200%2B%20Passing-success.svg)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)]()

<p align="center">
  <b>A secure, clinical decision-support and research platform for neuro-oncology MRI analysis.</b><br>
  Built with strict medical-safety guardrails, deterministic preprocessing, role-based access control, and complete auditability.
</p>

</div>

---

> ### ⚠️ Medical Disclaimer & Safety Invariants
>
> **BrainTwinX provides AI-assisted image analysis for RESEARCH AND CLINICAL DECISION-SUPPORT PURPOSES ONLY.**
>
> - AI-generated predictions and masks are **not a definitive medical diagnosis** and must never replace clinical judgement by a qualified healthcare professional.
> - The platform rigorously distinguishes **AI prediction** from **clinical diagnosis** across all API responses, database records, user interfaces, and generated reports.
> - The platform enforces a **fail-closed design**: unconfigured providers or missing model weights immediately return explicit typed errors (`503 NOT_READY`), never fabricated outputs.

---

## 📋 Table of Contents

- [About BrainTwinX](#-about-braintwinx)
- [System Architecture](#-system-architecture)
- [Project Status & Progress](#-project-status--progress)
- [Key Features](#-key-features)
- [Technology Stack](#-technology-stack)
- [Repository Structure](#-repository-structure)
- [Getting Started](#-getting-started)
  - [Prerequisites](#prerequisites)
  - [Environment Configuration](#environment-configuration)
  - [Running the Database](#running-the-database)
  - [Running the Spring Boot Backend](#running-the-spring-boot-backend)
  - [Running the FastAPI AI Service](#running-the-fastapi-ai-service)
- [API Reference](#-api-reference)
- [AI Pipeline & Inference](#-ai-pipeline--inference)
- [Security & Privacy Guardrails](#-security--privacy-guardrails)
- [Testing & Quality Assurance](#-testing--quality-assurance)
- [Release Notes](#-release-notes)
- [Documentation Index](#-documentation-index)
- [License](#-license)

---

## 🔬 About BrainTwinX

BrainTwinX bridges modern deep learning with strict clinical-safety requirements. Radiologists and neuro-oncology researchers face increasing scan volumes requiring precise detection, longitudinal trend estimation, and clear documentation.

BrainTwinX provides:
1. **Protected Patient Context**: Fully anonymised patient identifiers (`PAT-XXXXXX`) with zero Protected Health Information (PHI) leaked into URLs, logs, or AI payloads.
2. **Defensive MRI Ingestion**: Binary magic-byte inspection, dimensions/size limits, decompression bomb prevention, and SHA-256 deduplication.
3. **Transparent AI Pipeline**: Deterministic image preprocessing with strict version tracking, PyTorch CNN tumour classifiers, and explicit model registry provenance.
4. **Auditability & Integrity**: Immutable database audit logs capturing every security, ingestion, and inference event with correlation IDs.

---

## 🏛 System Architecture

```
                                  CLINICAL USER / RESEARCHER
                                              │
                                              ▼
                                   React Frontend (Vite)
                                              │  HTTPS / JWT
                                              ▼
                             ┌───────────────────────────────────┐
                             │    Spring Boot Application API    │
                             │        (Java 21 LTS / Boot 4)     │
                             └───────┬─────────────┬───────────┬─┘
                                     │             │           │
                     JDBC / Flyway   │             │ WebClient │ File Port
                                     ▼             │           ▼
                              MySQL 8.4 LTS        │     Filesystem / S3
                             (11 Core Tables)      │   (Hashed Scan Store)
                                                   ▼
                                    ┌─────────────────────────────┐
                                    │    FastAPI AI Microservice  │
                                    │       (Python / PyTorch)    │
                                    └──────────────┬──────────────┘
                                                   │
                                     ┌─────────────┼─────────────┐
                                     ▼             ▼             ▼
                                CNN Classifier  U-Net Seg   LSTM Trend
                                (Tumour Class) (Tumour Mask) (Historical)
```

### Architectural Highlights
- **Backend**: Spring Boot 4 application orchestrating business logic, RBAC, scan validation, analysis jobs, and audit logs.
- **AI Microservice**: Lightweight FastAPI service hosting PyTorch inference pipelines with lifecycle-managed model caches and SHA-256 weight integrity checks.
- **Data Persistence**: MySQL 8.4 schema governed by Flyway migrations with 14 enforced database check constraints and safety invariants.
- **Storage Port**: Decoupled `StorageService` interface allowing seamless swapping between secure local filesystem and cloud object storage (AWS S3 / GCP Cloud Storage).

---

## 📊 Project Status & Progress

The platform is actively developed using test-driven, verifiable delivery phases:

| Phase | Description | Status | Verification Status |
|---|---|:---:|---|
| **P1** | **Repository Audit & Scaffold** | ✅ Complete | Zero-baseline audit, verified toolchains, safe `.gitignore` |
| **P2** | **Architecture & Database** | ✅ Complete | Flyway migrations, 11 MySQL tables, 14 DB constraints |
| **P3** | **Authentication & RBAC** | ✅ Complete | JWT + BCrypt, deny-by-default, 3 clinical roles |
| **P4** | **Patient Management** | ✅ Complete | Public patient codes, zero PHI leaks, scope validation |
| **P5** | **MRI Upload & Validation** | ✅ Complete | Magic-byte checking, SHA-256 hashing, safe storage port |
| **P6** | **AI Service Foundation** | ✅ Complete | FastAPI skeleton, model registry, deterministic preprocessing |
| **P7** | **CNN Classification** | 🔄 Next | Model training harness & inference orchestration |
| **P8** | **U-Net Segmentation** | ⏳ Planned | Tumour mask overlay & pixel-area metrics |
| **P9** | **Longitudinal Tracking** | ⏳ Planned | Historical scans & LSTM trend analysis |
| **P10**| **Explanation Layer** | ⏳ Planned | Structured guard-railed diagnostic assistance |
| **P11**| **PDF Reporting** | ⏳ Planned | Downloadable clinical summary reports |
| **P12**| **Frontend Integration**| ⏳ Planned | High-fidelity React + TypeScript user interface |
| **P13**| **End-to-End Testing** | ⏳ Planned | Integration suites across all microservices |
| **P14**| **Security Hardening** | ⏳ Planned | Pen-testing, rate-limiting, audit log freeze |
| **P15**| **Docker & Deployment** | ⏳ Planned | Production multi-stage containers & Compose |
| **P16**| **Documentation & Release**| ⏳ Planned | Full user manuals and operational guides |

---

## ✨ Key Features

### 1. Role-Based Access Control (RBAC)
- Three operational roles:
  - `ADMIN`: User provisioning, system configuration, global audit inspection.
  - `DOCTOR`: Patient record creation, scan uploads, triggering AI analyses.
  - `RESEARCHER`: Anonymised data analysis and model performance evaluation.
- Stateless JWT authentication with standard deny-by-default Spring Security configuration.

### 2. Anonymised Patient Lifecycle
- Identifiers generated as public-safe strings (`PAT-XXXXXX`) avoiding primary database sequential IDs.
- Deletion flags and soft-archive workflows preserving audit integrity.
- Zero PHI in URLs, query strings, logs, or error responses.

### 3. Rigorous Image Validation Engine
- **MIME & Magic Bytes**: Rejects files with spoofed extensions (e.g. executable renamed as `.jpg`).
- **Decompression Bomb Defence**: Dimension thresholds (e.g., maximum 4096×4096) and byte-size caps.
- **Corrupt File Detection**: Decodes and verifies image streams before committing to storage.
- **SHA-256 Deduplication**: Detects identical scans uploaded to the same patient record.

### 4. Deterministic AI Preprocessing & Inference
- Versioned preprocessing (`v1.0.0`): converts 2-D scans (PNG/JPEG) to single-channel grayscale, resizes to 224×224 via bilinear interpolation, normalises pixel intensity to `[0.0, 1.0]`, and returns standardized PyTorch tensors.
- Model Registry verifies model weights via SHA-256 checksums on startup.
- Local stub inference mode (`AI_ALLOW_STUB_INFERENCE=true`) explicitly tags synthetic predictions as non-clinical for safe offline development.

---

## 🛠 Technology Stack

| Component | Technology | Version | Description |
|---|---|---|---|
| **Backend Framework** | Spring Boot | 4.1.1 | Reactive WebClient, Spring Data JPA, Spring Security |
| **Java Runtime** | OpenJDK / Temurin | 21 LTS (compiled `--release 21`) | Modern Java records, pattern matching |
| **AI Framework** | FastAPI | 0.115+ | High-performance Python asynchronous REST API |
| **Deep Learning** | PyTorch / torchvision | 2.13+ | CNN classifiers, U-Net segmentation models |
| **Database** | MySQL | 8.4 LTS | Flyway schema migrations, strict constraint checks |
| **Storage Engine** | Filesystem Port | Abstraction | Decoupled storage port for local files and object stores |
| **Testing** | JUnit 5 / Testcontainers / Pytest | Latest | Automated testing across units and real containers |

---

## 📁 Repository Structure

```
BrainTwinX/
├── ai-service/                   # FastAPI AI Microservice (PyTorch)
│   ├── app/
│   │   ├── api/                  # API routes & dependency injection
│   │   ├── config/               # Pydantic application settings
│   │   ├── inference/            # Model inference drivers (CNN, U-Net)
│   │   ├── models/               # PyTorch architectures & ModelRegistry
│   │   ├── preprocessing/        # Deterministic image preprocessing pipeline
│   │   └── schemas/              # Pydantic schemas (requests/responses)
│   ├── datasets/                 # Dataset setup guides and placeholders
│   ├── scripts/                  # Training and evaluation utilities
│   ├── tests/                    # Pytest test suite (unit & API tests)
│   └── requirements.txt          # Python dependencies
├── backend/                      # Spring Boot Application Backend
│   ├── src/main/java/com/braintwinx/
│   │   ├── client/               # WebClient integrations (AI Service)
│   │   ├── config/               # Security, Web, and Storage properties
│   │   ├── controller/           # REST Controllers (Auth, Patients, Scans, AI)
│   │   ├── dto/                  # Immutable Java records for request/response
│   │   ├── entity/               # JPA Entities mapping to MySQL schema
│   │   ├── exception/            # Global exception handlers & custom errors
│   │   ├── mapper/               # Entity <-> DTO converters
│   │   ├── repository/           # Spring Data JPA repositories
│   │   ├── security/             # JWT filters, BCrypt encoders, RBAC rules
│   │   ├── service/              # Core business services
│   │   └── validation/           # Image validation & security scanners
│   ├── src/main/resources/
│   │   ├── db/migration/         # Flyway SQL migrations (V1, V2)
│   │   └── application.yml       # Application configuration
│   ├── src/test/                 # Comprehensive JUnit 5 & Testcontainers tests
│   └── pom.xml                   # Maven project configuration
├── docker/                       # Docker & Compose definitions
│   └── docker-compose.yml        # Development environment services
├── docs/                         # Authoritative documentation package
│   ├── system-design/            # 16-part comprehensive system architecture
│   ├── ADR/                      # Architecture Decision Records
│   ├── CURRENT_STATE.md          # Audited project baseline and progress log
│   ├── DATASET_SETUP.md          # Dataset acquisition & model training guide
│   ├── TASKS.md                  # Granular task tracking per phase
│   └── TROUBLESHOOTING.md        # Real-world engineering issues & solutions
├── .env.example                  # Environment variable reference
├── README.md                     # Project overview and instructions
└── RELEASE.md                    # Official release documentation
```

---

## 🚀 Getting Started

### Prerequisites
- **Java**: OpenJDK 21+
- **Python**: Python 3.11+ (Python 3.14 compatible)
- **Node.js**: Node 20+ (for upcoming frontend)
- **Docker**: Docker Engine 24+ & Docker Compose

### Environment Configuration
Clone the repository and copy the environment template:
```bash
git clone https://github.com/Jaiswal-Sudhanshu/BrainTwinX.git
cd BrainTwinX
cp .env.example .env
```
Update `.env` with your desired credentials (e.g. generate a strong `JWT_SECRET` using `openssl rand -base64 48`).

### Running the Database
Start the containerised MySQL 8.4 instance:
```bash
docker compose -f docker/docker-compose.yml up -d mysql
```

### Running the Spring Boot Backend
```bash
cd backend
./mvnw spring-boot:run
```
The backend starts on `http://localhost:8080`. Flyway automatically runs database migrations on startup.

### Running the FastAPI AI Service
```bash
cd ai-service
python -m venv .venv
# On Windows:
.venv\Scripts\activate
# On Linux/macOS:
source .venv/bin/activate

pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```
The AI service interactive documentation is available at `http://localhost:8000/docs`.

---

## 🔌 API Reference

### Authentication & Authorization
| Method | Endpoint | Description | Roles |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Register a new user account | Public |
| `POST` | `/api/v1/auth/login` | Authenticate user & issue JWT | Public |
| `POST` | `/api/v1/auth/refresh` | Refresh expired access token | Public |

### Patient Management
| Method | Endpoint | Description | Roles |
|---|---|---|---|
| `GET` | `/api/v1/patients` | Paginated list of patients | `ADMIN`, `DOCTOR`, `RESEARCHER` |
| `POST` | `/api/v1/patients` | Create a new patient profile | `ADMIN`, `DOCTOR` |
| `GET` | `/api/v1/patients/{patientCode}` | Retrieve patient by code | `ADMIN`, `DOCTOR`, `RESEARCHER` |
| `PUT` | `/api/v1/patients/{patientCode}` | Update patient details | `ADMIN`, `DOCTOR` |
| `DELETE`| `/api/v1/patients/{patientCode}` | Soft-delete / archive patient | `ADMIN` |

### MRI Scan Management
| Method | Endpoint | Description | Roles |
|---|---|---|---|
| `POST` | `/api/v1/patients/{patientCode}/scans` | Upload and validate MRI scan | `ADMIN`, `DOCTOR` |
| `GET` | `/api/v1/patients/{patientCode}/scans` | List all scans for a patient | `ADMIN`, `DOCTOR`, `RESEARCHER` |
| `GET` | `/api/v1/scans/{scanId}` | Get scan metadata & status | `ADMIN`, `DOCTOR`, `RESEARCHER` |
| `GET` | `/api/v1/scans/{scanId}/file` | Download original scan file | `ADMIN`, `DOCTOR` |

### AI Inference & Classification
| Method | Endpoint | Description | Roles |
|---|---|---|---|
| `POST` | `/api/v1/scans/{scanId}/classify` | Trigger tumour classification job | `ADMIN`, `DOCTOR` |
| `GET` | `/api/v1/analyses/{jobId}` | Query status and result of analysis job | `ADMIN`, `DOCTOR`, `RESEARCHER` |

### AI Service Internal Endpoints
| Method | Endpoint | Description | Access |
|---|---|---|---|
| `GET` | `/internal/ai/v1/health` | Liveness health check | Internal / Authorized |
| `GET` | `/internal/ai/v1/ready` | Readiness check (503 if weights absent) | Internal / Authorized |
| `POST` | `/internal/ai/v1/classify` | Execute CNN tumour classification | Internal / Authorized |

---

## 🤖 AI Pipeline & Inference

### 1. Preprocessing Pipeline (`v1.0.0`)
- **Input**: Raw 2-D MRI images in PNG or JPEG format.
- **Transformations**:
  1. Conversion to 1-channel Grayscale (L).
  2. Resizing to standard input dimensions ($224 \times 224$) via bilinear interpolation.
  3. Intensity normalisation to $[0.0, 1.0]$.
  4. Conversion to PyTorch Tensor shape $(1, 1, 224, 224)$.
- **Determinism**: Guaranteed identical tensor output given identical input image and version tag.

### 2. CNN Classifier Architecture
- Custom 4-stage convolutional backbone with Batch Normalisation, ReLU activation, MaxPooling, Dropout ($p=0.4$), and linear classification head.
- Outputs softmax probabilities over 4 tumour classes:
  - `NO_TUMOUR`
  - `GLIOMA`
  - `MENINGIOMA`
  - `PITUITARY`

### 3. Model Weight Integrity & Registry
- The Model Registry enforces SHA-256 checksum verification before loading weights.
- When weights are not yet trained or mounted, endpoints return `503 Service Unavailable` with a typed error explaining model unreadiness.
- For complete training guides and dataset acquisition, refer to [`docs/DATASET_SETUP.md`](docs/DATASET_SETUP.md).

---

## 🔒 Security & Privacy Guardrails

- **Deny-by-Default**: Every HTTP route in Spring Boot requires explicit authorization; unmapped routes are denied by default.
- **Zero PHI in Diagnostics**: Stack traces, error bodies, and logging events are sanitised to prevent patient names or demographic info from leaking.
- **Audit Logging**: Crucial security and business events (`SCAN_UPLOADED`, `SCAN_VALIDATION_FAILED`, `ANALYSIS_REQUESTED`, `PATIENT_CREATED`, etc.) are written to immutable audit records.
- **Tamper-Resistant Storage**: Stored scans are isolated using server-generated UUID storage names and checked against their SHA-256 hashes.

---

## 🧪 Testing & Quality Assurance

BrainTwinX follows strict verification criteria. No phase is marked complete without full automated test coverage.

### Backend Verification (JUnit 5, Mockito & Testcontainers)
Run the complete backend test suite:
```bash
cd backend
./mvnw clean test
```
*Current test metrics:* **201 passing tests** (111 unit tests + 90 integration tests), 0 failures, 0 errors.

### AI Service Verification (Pytest)
Run the AI microservice test suite:
```bash
cd ai-service
pytest -v
```
*Current test metrics:* **7 passing tests** covering configuration, authentication, readiness probes, deterministic preprocessing, and input validation.

---

## 📦 Release Notes

### Current Release: `v0.1.0-alpha`
- **Milestone**: Foundational Core Platform, Patient Management & AI Inference Service.
- **Delivered**:
  - Full Spring Boot 4 / Java 21 architecture with MySQL 8.4 database.
  - JWT authentication and 3-tier Role-Based Access Control.
  - Anonymised patient lifecycle management.
  - Secure MRI upload, image integrity verification, and local storage engine.
  - FastAPI AI microservice with PyTorch CNN model registry and deterministic preprocessing.
  - Comprehensive documentation and system architecture design.

For full release notes, see [`RELEASE.md`](RELEASE.md).

---

## 📚 Documentation Index

| File | Purpose |
|---|---|
| [`RELEASE.md`](RELEASE.md) | Official release announcement & detailed changelog |
| [`docs/CURRENT_STATE.md`](docs/CURRENT_STATE.md) | Verified audit baseline and progress history |
| [`docs/DATASET_SETUP.md`](docs/DATASET_SETUP.md) | Instructions for acquiring MRI datasets & model training |
| [`docs/TASKS.md`](docs/TASKS.md) | Granular checklist of implemented and planned tasks |
| [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) | Engineering troubleshooting records and toolchain notes |
| [`docs/system-design/`](docs/system-design/) | 16-part architectural system design specifications |
| [`docs/ADR/`](docs/ADR/) | Architecture Decision Records justifying technical choices |

---

## 📄 License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.
