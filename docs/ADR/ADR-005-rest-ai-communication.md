# ADR-005: REST over internal HTTP for backend↔AI communication

**Status:** Accepted
**Date:** 2026-08-22
**Deciders:** Engineering owner

---

## Context

The Spring Boot backend ([ADR-002](./ADR-002-spring-boot-backend.md)) must invoke the Python
AI service ([ADR-003](./ADR-003-python-ai-service.md)) for classification, segmentation, and
trend estimation. Inference is slow relative to an HTTP request, so the backend already runs
analysis asynchronously as a job (brief §20).

## Problem

What transport and interaction style should connect the two services?

## Options considered

| Option | Assessment |
|---|---|
| **REST/JSON over internal HTTP** | Simple, debuggable with ordinary tools, no schema-compiler step, and easy to version by URL path. Verbose on the wire; no compile-time contract enforcement. |
| **gRPC** | Efficient binary transport with a generated, compile-time-checked contract. Adds protobuf tooling to both build pipelines and makes ad-hoc debugging harder. The efficiency gain is irrelevant here: payloads are small and calls are dominated by model execution time. |
| **Message queue (RabbitMQ / Kafka)** | Natural fit for async work and would decouple the services fully. Rejected for now: brief §20 and §55 explicitly warn against introducing infrastructure without justification, and job state already lives durably in MySQL (`ASSUMPTIONS.md` A-9). Adding a broker would add an operational component without solving a problem the system currently has. |
| **Shared filesystem + polling** | No network dependency. Rejected: it turns a clear service boundary into an implicit coupling through directory layout, with no request/response contract and awkward failure semantics. |

## Decision

**REST/JSON over HTTP on an internal-only network**, with:

- Versioned paths: `/internal/ai/v1/{predict,segment,forecast,health,ready}`.
- **Explicit request timeouts** on every call, configured via `AI_SERVICE_TIMEOUT_MS`.
- Typed failure mapping: any transport failure, timeout, or non-2xx becomes a domain
  `AI_SERVICE_UNAVAILABLE` error, never a leaked exception or an indefinite wait.
- A **shared internal secret** (`AI_SERVICE_API_KEY`) so the AI service rejects unauthenticated
  callers even if the network boundary is misconfigured.
- **Async orchestration stays in the backend.** The AI service exposes synchronous endpoints;
  the backend calls them from a worker processing a queued job. The AI service therefore holds
  no job state.

## Reason

The interaction is a small number of request/response calls with small payloads and long
server-side compute. That profile makes transport efficiency close to irrelevant and
observability valuable — a `curl` against `/internal/ai/v1/ready` is worth more during an
incident than a few saved bytes.

Keeping asynchrony in the backend rather than the AI service is the more consequential half of
this decision. It means:

1. There is exactly one place where job state lives (MySQL) and one state machine governing it.
2. The AI service stays stateless, so it can be scaled or restarted freely without stranding
   work.
3. Retry policy, idempotency, and status reporting are implemented once, in the service that
   already owns the database.

Rejecting a broker now is deliberate rather than lazy: the queue abstraction is defined behind
an interface, so a broker can be substituted later without changing callers, and
`system-design/09-SCALABILITY-DESIGN.md` records the measured trigger that would justify it.

## Trade-offs

**Accepted:**

- No compile-time contract between services. Mitigated by versioned Pydantic schemas on the
  Python side, explicit DTOs on the Java side, and contract tests exercising both.
- JSON serialisation overhead per call — negligible against inference latency.
- HTTP timeouts must be tuned; too short fails valid slow inference, too long ties up a worker.
  Made configurable rather than hard-coded.
- An internal HTTP endpoint is reachable by anything on the same network. Mitigated by network
  placement **and** the shared secret — the path prefix alone is not treated as protection.

## Consequences

- `/internal/ai/**` must never be exposed publicly (brief §17). This is enforced by deployment
  topology and the shared secret, not by naming convention.
- The AI service must return structured errors the backend can map, rather than free-form text.
- Every inference response carries `modelName`, `modelVersion`, and `preprocessingVersion`, so
  the backend can persist full provenance (brief §22).
- Because the AI service is stateless, a mid-inference crash leaves the job `RUNNING` in MySQL.
  A reaper (`AnalysisJobRepository.findStaleRunning`) returns such jobs to a predictable state,
  as brief §39 requires.
- `/ready` returning false must cause the backend to fail the job with a typed error rather than
  queue indefinitely.

## Verification

- Backend→AI client and internal endpoints are **NOT STARTED** (Phase 6). See `docs/TASKS.md`.
- The stale-job reaper query exists in `AnalysisJobRepository` but has no test yet.
