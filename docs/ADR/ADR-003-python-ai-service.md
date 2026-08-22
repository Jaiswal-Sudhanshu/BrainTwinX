# ADR-003: A separate Python service for AI inference

**Status:** Accepted
**Date:** 2026-08-22
**Deciders:** Engineering owner
**Supersedes:** —

---

## Context

BrainTwinX performs three distinct ML tasks: tumour classification (CNN), tumour
segmentation (U-Net), and longitudinal trend estimation (LSTM). The business/API layer is
Spring Boot ([ADR-002](./ADR-002-spring-boot-backend.md)).

Verified on the host: `torch 2.13.0` and `numpy 2.5.2` have cp314 wheels, so the Python ML
stack is usable on the installed Python 3.14.5.

## Problem

Where should model loading, preprocessing, and inference execute, and should it be a
separate deployable unit?

## Options considered

| Option | Assessment |
|---|---|
| **Separate Python service** | Native access to PyTorch and the imaging ecosystem. Adds a network boundary, a second runtime, and serialisation cost. |
| **Java-native inference (DJL / ONNX Runtime for Java)** | Single service, no network hop. But training and evaluation would still happen in Python, so models would be authored in one ecosystem and served in another — every export becomes a translation step that can silently change preprocessing or numerics. That is exactly the class of bug that produces a model which evaluates well and serves badly. |
| **Python for everything** (drop Spring Boot) | See [ADR-002](./ADR-002-spring-boot-backend.md); rejected on security/transaction grounds. |
| **Embedded Python via GraalPy / JEP** | Avoids the network hop while keeping Python. Immature for the PyTorch native-extension stack, and couples two runtimes' memory and GIL behaviour inside one process — a hard failure mode to debug in production. |

## Decision

Run a **separate Python service** (FastAPI) that owns validation-adjacent preprocessing,
model loading, and inference, exposed over internal-only HTTP.

Supporting decisions:

- **Models are loaded once per worker**, not per request (brief §37).
- **The container pins its own Python version**; local dev may differ. Reproducibility must
  not depend on the host interpreter (`ASSUMPTIONS.md` A-12).
- **`/ready` reflects reality.** If weights are absent or fail a shape/checksum check, the
  service reports NOT READY and the backend degrades predictably rather than hanging.

## Reason

The decisive argument is **train/serve consistency**. The preprocessing pipeline is
versioned and must be byte-identical between training and inference (brief §10); a model is
only valid for the preprocessing contract it was trained against. Keeping training and
serving in the same language and the same code path removes an entire category of silent
divergence.

Secondary reasons:

1. **Failure isolation.** A model that exhausts memory or crashes takes down inference, not
   authentication, patient records, or report download.
2. **Independent scaling.** Inference is CPU/GPU-bound and bursty; the API is I/O-bound.
   Brief §55 anticipates an AI worker pool scaling separately from API instances.
3. **Independent deployment cadence.** Swapping a model version must not require
   redeploying the business API.

## Trade-offs

**Accepted:**

- Two runtimes to build, test, and operate.
- A network boundary that can fail — mitigated with explicit timeouts and a typed
  `AI_SERVICE_UNAVAILABLE` mapping, so a stalled inference surfaces as a predictable state
  rather than a hung request (brief §39).
- Serialisation cost on every inference. Acceptable: it is small relative to model
  execution time.
- Contract drift risk between the two services — mitigated by versioned request/response
  schemas and contract tests.

**Explicitly not claimed:** this is not a microservice architecture. There are exactly two
services, split along a real technical seam. Brief §3 warns against decomposition for its
own sake, and no further splitting is planned.

## Consequences

- Internal AI endpoints live under `/internal/ai/v1/**` and **must not be publicly
  exposed** (brief §17). Enforced by network placement plus a shared internal secret, not
  by obscurity.
- The AI service is stateless per request; all durable state stays in MySQL.
- Preprocessing version is returned with every inference and persisted with every result,
  making outputs reproducible (brief §22).
- **Currently blocked:** no trained weights exist because no dataset is available
  (`ASSUMPTIONS.md` A-1). The service is built to report NOT READY and return a typed error
  rather than fabricate a prediction.

## Verification

- `torch` cp314 wheel availability — **CONFIRMED** (2.13.0) via `pip index versions torch`.
- Service implementation — **NOT STARTED** (Phase 6). See `docs/TASKS.md`.
