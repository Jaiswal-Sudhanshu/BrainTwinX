# ADR-002: Spring Boot for the application API

**Status:** Accepted
**Date:** 2026-08-22
**Deciders:** Engineering owner
**Supersedes:** —

---

## Context

BrainTwinX needs a central business/application API that owns authentication,
authorisation, patient and scan management, AI orchestration, report generation, and audit
logging. It handles sensitive medical data, so the framework's security and transaction
story matters more than its raw throughput.

The host toolchain has JDK 25.0.4 (Temurin) and Maven 3.9.16 already installed.

## Problem

Which platform should own the business/API layer, given that a separate Python service will
own ML inference (see [ADR-003](./ADR-003-python-ai-service.md))?

## Options considered

| Option | Assessment |
|---|---|
| **Spring Boot (Java)** | Mature declarative security with deny-by-default filter chains; first-class transaction management; JPA + Flyway; strong typing; Testcontainers integration. Verbose, and heavier startup than the alternatives. |
| **Python (FastAPI) for everything** | One language across API and ML, so no service boundary and no serialisation cost. But it puts CPU-bound inference in the same process as the request-handling API, where a long-running model call can starve request threads, and it forgoes compile-time type enforcement across a large domain model. |
| **Node.js (NestJS)** | Good ergonomics and a strong ecosystem. TypeScript types vanish at runtime, so the boundary validation this domain needs must be re-implemented at runtime anyway; transaction handling is less mature than JPA's. |
| **Go** | Excellent performance and deployment simplicity. Weaker ORM/migration ecosystem, and more of the security scaffolding (RBAC, filter chains, method security) would be hand-rolled — more code to get wrong in the layer where mistakes are most costly. |

## Decision

Use **Spring Boot 4.1.1**, targeting **Java 21 (LTS)** via `maven.compiler.release=21`,
built by the installed JDK 25.

Two supporting choices:

- **No Lombok.** Java 21 records cover the DTO case, and avoiding an annotation processor
  removes the most common source of breakage when a new JDK lands. Keeps dependencies
  minimal per brief §60.
- **Spring Boot 4.1.1 specifically, not 3.5.x.** Verified empirically rather than assumed:
  4.1.x supports a JDK 25 runtime, which matters because test-time bytecode instrumentation
  (ByteBuddy, via Mockito) must understand the running JVM. A Boot 3.5 line pinned to an
  older ByteBuddy would risk test-only failures on this host.

## Reason

The deciding factor is that **the security and consistency layer is where a defect is most
expensive in this domain**, and Spring Boot provides more of it declaratively than the
alternatives:

1. `SecurityFilterChain` makes deny-by-default the actual default, so a forgotten
   annotation fails closed rather than open — the failure mode that produces most
   real-world access-control defects.
2. Declarative `@Transactional` boundaries matter for writes that must not partially
   persist, e.g. a prediction plus its segmentation metadata (brief §51).
3. JPA with `ddl-auto=validate` plus Flyway means entity/schema drift breaks the build
   instead of surfacing at runtime against a medical database.
4. Testcontainers integration lets integration tests run against real MySQL, so
   CHECK-constraint-based safety invariants are actually exercised.

The verbosity cost is real but falls on boilerplate, not on logic — and rejecting Lombok
is an accepted increase in that boilerplate in exchange for toolchain robustness.

## Trade-offs

**Accepted:**

- More ceremony per class than FastAPI or NestJS.
- Slower startup (~2–4 s) than Go or Node.
- Two languages in the system, so contributors need both. Mitigated by a narrow, versioned
  REST contract between them (see [ADR-005](./ADR-005-rest-ai-communication.md)).
- Boot 4.x is a recent major line, so community material is thinner than for 3.x. Accepted
  because JDK-25 runtime support was the harder constraint.

**Rejected trade-off:** running inference inside the API process. Cheaper to build, but it
couples request-thread availability to model latency and forces the API's deployment
cadence onto the ML stack.

## Consequences

- Backend is Maven-built; the **Maven Wrapper is committed** so builds do not depend on the
  Maven copy currently living in `~/Downloads` (`CURRENT_STATE.md` risk R-6).
- Java 21 target keeps bytecode portable regardless of the building JDK.
- Bean Validation is available at the boundary, satisfying brief §18.
- Hibernate `validate` is set in **all** profiles, including dev — allowing schema mutation
  locally would let drift go unnoticed until it reached an environment where it mattered.
- Any future Spring Boot upgrade must re-verify JDK compatibility by running a build, not
  by reading release notes.

## Verification

- `mvn test-compile` — **PASSED**: 38 main + 3 test sources, `release 21`, zero warnings.
- Dependency resolution against Maven Central — **PASSED**.
- Full `mvn verify` including Testcontainers MySQL — see `docs/TASKS.md` for current status.
