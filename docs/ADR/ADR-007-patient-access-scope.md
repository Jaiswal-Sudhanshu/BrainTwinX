# ADR-007: Patient access scope — least privilege by caseload

**Status:** Accepted
**Date:** 2026-08-25
**Deciders:** Engineering owner
**Supersedes:** —

---

## Context

Phase 4 introduces the first resource an authenticated user can read, write, and list. The security
chain is deny-by-default ([ADR-006](./ADR-006-jwt-authentication.md)), so every patient endpoint
must grant access explicitly — which forces the question of *which* patients each role may see.

The project brief specifies three roles (§7) and requires IDOR protection (§54), but does **not**
state which patients a given clinician should be able to access. That is a clinical/organisational
policy question, not an engineering one.

## Problem

What is the default access scope for patient records, given that the brief does not specify one and
brief §58 forbids inventing medical or business requirements?

## Options considered

| Option | Assessment |
|---|---|
| **All authenticated users see all patients** | Simplest, and arguably matches how a small clinic operates. But it makes the IDOR requirement (§54) vacuous — there would be no "out of scope" to protect — and it is the least reversible choice, because narrowing it later breaks clients that came to rely on it. |
| **DOCTOR scoped to own caseload; ADMIN unrestricted; RESEARCHER read-only across all** *(chosen)* | Least privilege by default. Gives IDOR a concrete, testable meaning. Costs a scope check on every access and may be too narrow for real clinical workflows. |
| **Explicit per-patient access grants (care teams)** | The most clinically accurate model — real care involves handover, coverage, and multidisciplinary teams. Rejected as premature: it needs a grants table, a grant-management UI, and a policy for who may grant, none of which the brief describes. Inventing that would be exactly the overreach §58 warns against. |
| **Department or organisational-unit scoping** | Realistic for a hospital. Rejected for the same reason: the brief models no organisational hierarchy, so any structure would be fabricated. |

## Decision

| Role | Scope |
|---|---|
| **ADMIN** | Unrestricted read and write |
| **DOCTOR** | Read and write **restricted to patients they created** |
| **RESEARCHER** | **Read-only across all patients**; may not create, update, or archive |

Two supporting decisions:

- **Out-of-scope access returns 404, never 403.** A 403 confirms the record exists, which would let
  a caller enumerate another clinician's caseload one code at a time.
- **Scope is enforced in `PatientService`, not the controller.** It therefore applies however the
  operation is reached, including from a future scheduled job or internal caller that does not pass
  through HTTP. The `@PreAuthorize` annotations on the controller are a coarse first gate on *role*
  only.

## Reason

Two arguments decided it.

**Least privilege is the safer default when the requirement is unstated.** If this scope proves too
narrow, the symptom is a clinician who cannot see a colleague's patient — visible, immediately
reported, and fixed by widening a single method. If it were too broad, the symptom is unauthorised
access to medical records that nobody notices. Those failure modes are not symmetric, and the
reversible one is the right default.

**RESEARCHER is read-only across all patients rather than scoped**, because scoping research to
"records you created" is incoherent — a researcher creates none — and because the records are
already data-minimised (`ASSUMPTIONS.md` A-8): no name, no contact details, no address, no
free-text history. Cross-cohort read of pseudonymised records is the actual research use case.
Write access is withheld because nothing in the brief describes a researcher creating clinical
records.

**A 404 for forbidden access** costs some diagnostic clarity, which is why the real reason is logged
server-side with the username and attempted code. The client sees an indistinguishable response;
an administrator reading logs sees exactly what happened.

## Trade-offs

**Accepted:**

- **The caseload model may not match real clinical workflow.** Handover and coverage are genuine
  needs this does not serve. Documented as `ASSUMPTIONS.md` A-16 and flagged for review by a
  clinical stakeholder rather than silently treated as correct.
- A scope check on every patient access. Cheap: the creating user is fetched via `@EntityGraph` in
  the same query, so it adds no round trip.
- 404-for-forbidden makes support marginally harder. Mitigated by server-side logging.
- Listing must be scoped **in the query**, not filtered afterwards. Filtering in memory would still
  transfer other clinicians' rows out of the database, and a paging bug would then leak them.

**Explicitly not decided here:** whether a doctor should see patients whose *scans* they analysed
but whose record they did not create. That is deferred to Phase 5, where scan ownership becomes
concrete.

## Consequences

- `PatientRepository` exposes both `findByStatus` and `findByCreatedByAndStatus`; services must pick
  based on scope rather than filtering.
- Any future patient-adjacent resource (scans, reports) must apply the same scope, or it becomes a
  way around this one. Phase 5 must reuse the same check rather than reimplementing it.
- Widening the model later requires only changing `hasUnrestrictedScope`, which is why that
  predicate is a single named method rather than an inline condition.
- If a care-team model is adopted, this ADR should be superseded rather than edited.

## Verification

- `PatientServiceTest` — 8 scope tests including doctor-cannot-read-others (asserting
  `PATIENT_NOT_FOUND`, not `FORBIDDEN`), researcher write refusal, and rejection of a principal that
  no longer resolves to an enabled user.
- `PatientManagementIT` — IDOR across read, update, archive, and listing; plus an assertion that the
  denied response is **identical** to a genuinely-absent response.
- Exact counts: [`TASKS.md`](../TASKS.md) verification log.
