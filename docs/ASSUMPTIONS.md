# BrainTwinX — Assumptions & Decisions Under Ambiguity

**Last updated:** 2026-08-22

Per project brief §58: where requirements are ambiguous, the safest technically reasonable
interpretation is chosen and recorded here rather than invented silently. **No medical or
business requirement is invented.** Where a clinical judgement would be required, the
decision is deferred to a qualified professional and the system is made configurable
instead of opinionated.

Each entry states the **ambiguity**, the **assumption taken**, the **reason**, and how to
**revisit** it.

---

## A-1 — No MRI dataset is available

| | |
|---|---|
| **Ambiguity** | The brief requires trained CNN / U-Net / LSTM models, but no dataset exists in the repository or on the host. |
| **Assumption** | Build the complete inference architecture — preprocessing, model interfaces, loaders, registry, async orchestration, persistence, and a genuinely runnable training + evaluation harness — but ship **no trained weights and no accuracy claims**. |
| **Reason** | Brief §11 forbids presenting untrained output as valid; §23 forbids reporting metrics without a real evaluation; §24 forbids downloading arbitrary data and calling it production-quality. Confirmed with the project owner on 2026-08-22. |
| **Consequence** | Analysis endpoints return a typed, documented error when weights are absent. They do **not** fabricate a prediction. |
| **Revisit when** | A licensed dataset is supplied. Then run the training and evaluation pipelines and populate `docs/AI_EVALUATION.md` with real numbers. See `docs/DATASET_SETUP.md`. |

---

## A-2 — Development stub inference must be inert by default

| | |
|---|---|
| **Ambiguity** | Brief §11 permits "a clearly marked development/test mode" but also forbids presenting test output as clinically valid. |
| **Assumption** | A stub inference path may exist **only** for automated testing and local wiring verification. It is: (a) disabled unless an explicit non-default config flag is set; (b) never enabled in the production profile; (c) tagged in every response payload as non-clinical synthetic output; (d) covered by a test asserting it is off by default. |
| **Reason** | The safest reading. The value of a stub is verifying plumbing; the risk is a synthetic number reaching a clinician. Gating plus explicit tagging keeps the former and removes the latter. |
| **Revisit when** | Real weights land — the stub then exists purely for tests. |

---

## A-3 — No LLM provider is configured

| | |
|---|---|
| **Ambiguity** | Brief §14 requires an explanation layer but names no provider and supplies no credentials. |
| **Assumption** | Implement a provider-agnostic `ExplanationProvider` port that **fails closed**: when unconfigured, the explanation step returns a typed error and the surrounding analysis and report still complete, with the explanation section marked "Not available". |
| **Reason** | Hard-coding a vendor would be an unrequested architectural commitment; failing the whole pipeline for a missing optional enrichment would be poor fail-safe design. |
| **Revisit when** | A provider and key are supplied via environment configuration. |

---

## A-4 — The LLM never receives the MRI image

| | |
|---|---|
| **Ambiguity** | Brief §14 says the LLM "must NOT directly inspect the MRI", without specifying enforcement. |
| **Assumption** | Enforce structurally, not by instruction: the explanation input is an **allow-listed, strongly typed struct** (predicted class, confidence, segmentation summary, model versions, trend status). Image bytes, file paths, and arbitrary free text are not representable in that type. |
| **Reason** | A prompt instruction is advisory; a type boundary is enforceable and testable. Also closes the prompt-injection path through user-supplied text fields. |
| **Revisit when** | Never, without an ADR — this is a medical-safety invariant. |

---

## A-5 — Minimum history for longitudinal forecasting

| | |
|---|---|
| **Ambiguity** | Brief §13 requires detecting "insufficient historical data" but does not define the threshold, which is a clinical/statistical judgement. |
| **Assumption** | Make it **configuration, not a hard-coded clinical opinion.** Default to a conservative minimum of **3 valid observations** spanning a configurable minimum interval; below either bound, return `INSUFFICIENT_HISTORY`. |
| **Reason** | Two points define a line and would let the system emit a "trend" with no evidence of one. Three is the minimum that permits any notion of non-linearity. The number itself is a domain decision, so it is exposed for a qualified professional to set rather than fixed by an engineering guess. |
| **Revisit when** | A clinician or statistician specifies an appropriate threshold. |

---

## A-6 — Supported MRI input formats

| | |
|---|---|
| **Ambiguity** | The brief says "MRI scans" without naming formats. DICOM and NIfTI are the clinical standards; the classification datasets typically referenced for this task are 2-D PNG/JPEG slices. |
| **Assumption** | Phase 5 supports **2-D image formats (PNG, JPEG)** with magic-byte verification. DICOM/NIfTI are explicitly **out of scope** for now, recorded in `LIMITATIONS.md`, with the `StorageService` and preprocessing pipeline designed so a format handler can be added without redesign. |
| **Reason** | Implementing partial DICOM handling would be worse than not implementing it: DICOM carries embedded PHI requiring de-identification, and mishandling it is a privacy risk. Narrow, correct scope beats broad, unsafe scope. |
| **Revisit when** | Clinical integration is required. Would need an ADR plus a de-identification design. |

---

## A-7 — `tumorArea` units

| | |
|---|---|
| **Ambiguity** | Brief §12 shows `"tumorArea": 12345` with no unit. |
| **Assumption** | Report area in **pixels of the preprocessed image**, with the field explicitly named and documented as such, alongside the preprocessing version and mask dimensions needed to interpret it. |
| **Reason** | Physical area (mm²) requires pixel-spacing metadata that 2-D PNG/JPEG inputs do not carry (see A-6). Emitting a mm² figure derived from an assumed spacing would be a fabricated measurement. |
| **Revisit when** | DICOM support lands and real pixel spacing is available. |

---

## A-8 — Patient identity model

| | |
|---|---|
| **Ambiguity** | Brief §8 requires "a stable internal ID" and a public-safe `patientCode`, and §26 requires data minimisation, without specifying which demographics to store. |
| **Assumption** | Store the **minimum** needed for the platform to function: `patientCode` (public-safe, non-sequential), an internal surrogate key, year of birth or age band rather than full DOB where feasible, and sex/gender only as an optional field used for clinical context. No contact details, no address, no free-text medical history. |
| **Reason** | Brief §26 mandates minimisation. Every stored field is a liability; fields not required by a described feature are not collected. |
| **Revisit when** | A feature genuinely requires an additional field — which should be justified in the same change. |

---

## A-9 — Asynchronous analysis without new infrastructure

| | |
|---|---|
| **Ambiguity** | Brief §20 requires async analysis and permits polling initially; §55 warns against unnecessary infrastructure. |
| **Assumption** | Implement async with an **in-process job model backed by the existing database** (job state in a table, Spring `@Async` executor), with status polling. No Redis, no Kafka, no RabbitMQ. |
| **Reason** | Explicitly sanctioned by §20 ("polling is acceptable") and §55. Job state in MySQL survives restarts and needs no new operational component. The queue port is designed so a broker can be substituted later behind the same interface. |
| **Revisit when** | Measured throughput justifies a broker — documented as a trigger in `system-design/09-SCALABILITY-DESIGN.md`. |

---

## A-10 — Object storage abstraction, filesystem implementation

| | |
|---|---|
| **Ambiguity** | Brief §4 lists "File Storage" and §55 mentions object storage in the production topology, but no cloud provider is specified. |
| **Assumption** | Define a `StorageService` port; implement it over the **local filesystem** for now. No cloud SDK dependency is added. |
| **Reason** | Keeps local development simple (§55) and dependencies minimal (§60), while the port makes an S3/Azure Blob implementation a drop-in addition. |
| **Revisit when** | A deployment target is chosen. |

---

## A-11 — Java language level

| | |
|---|---|
| **Ambiguity** | Host JDK is 25.0.4; the brief names no Java version. |
| **Assumption** | Target **Java 21** (LTS) via `--release 21`, built by the installed JDK 25, on a Spring Boot version whose Java support is **verified by an actual build** rather than assumed. Commit the Maven Wrapper so builds do not depend on the Maven copy currently living in `Downloads`. |
| **Reason** | An LTS target is the reproducible choice for a platform intended to be deployable; `--release` guarantees the bytecode target regardless of host JDK. |
| **Revisit when** | A newer LTS is adopted. |

---

## A-12 — Python runtime is pinned in the container, not inherited from the host

| | |
|---|---|
| **Ambiguity** | Host Python is 3.14.5, which is very new for the ML ecosystem. |
| **Assumption** | The AI service Docker image **pins its own Python version**. Local development on 3.14 is supported — `torch 2.13.0` and `numpy 2.5.2` cp314 wheels were verified available — but CI and production do not depend on the host interpreter. |
| **Reason** | Reproducibility. A host upgrade must not be able to break the service. |
| **Revisit when** | The pinned version reaches end of support. |

---

## A-13 — "Archive/deactivate patient" is a soft delete

| | |
|---|---|
| **Ambiguity** | Brief §8 lists "Archive/deactivate", while §17 exposes `DELETE /api/v1/patients/{id}`. |
| **Assumption** | `DELETE` performs a **soft archive**: the record is marked inactive and excluded from default listings, but retained. No hard delete is exposed. |
| **Reason** | Medical records have retention obligations, and predictions/reports reference patients — a hard delete would orphan audit history, which §27 requires be preserved. |
| **Revisit when** | A data-retention or erasure policy is defined; erasure would need a dedicated, audited workflow. |

---

## A-14 — Git remains local; no remote is configured

| | |
|---|---|
| **Ambiguity** | Brief §41 specifies a Git branching and commit-message convention but names no remote. |
| **Assumption** | Initialise the repository locally and commit per phase using conventional commit prefixes. **Nothing is pushed anywhere.** |
| **Reason** | Publishing a repository containing a medical-domain application is an outward-facing action that was not requested. |
| **Revisit when** | The owner supplies a remote and asks for a push. |

---

## A-15 — Interpretation of "no fake production predictions"

| | |
|---|---|
| **Ambiguity** | Brief §30 and §59 require that "no fake production predictions exist" — a property that must be verifiable, not merely asserted. |
| **Assumption** | Treat this as a **testable invariant**: automated tests assert that (a) no confidence or probability value is produced by any literal or random source on a production path; (b) the stub provider is disabled under the production profile; (c) responses lacking real model output carry an explicit unavailability status rather than values. |
| **Reason** | A claim in documentation is not evidence. Encoding the rule as a test is what makes it true and keeps it true. |
| **Revisit when** | Never — this is the project's core integrity property. |

---

## Medical disclaimer

BrainTwinX provides AI-assisted image analysis for research and decision-support
purposes. AI-generated results are not a definitive medical diagnosis and should not
replace evaluation by a qualified healthcare professional.
