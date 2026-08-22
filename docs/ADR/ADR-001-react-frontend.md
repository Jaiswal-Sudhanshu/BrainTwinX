# ADR-001: React with TypeScript for the frontend

**Status:** Accepted
**Date:** 2026-08-22
**Deciders:** Engineering owner

---

## Context

BrainTwinX needs a clinical/research dashboard: patient management, MRI upload, analysis
status, result presentation with a segmentation-mask overlay viewer, and report access. The
brief specifies React (§4, §6) and requires a serious healthcare application appearance
rather than a generic AI landing page (§6).

Node 24.16.0 and npm 12.0.2 are present on the host.

## Problem

What frontend stack, and how much type safety at the API boundary?

## Options considered

| Option | Assessment |
|---|---|
| **React + TypeScript (Vite)** | Named in the brief. Large ecosystem; strong canvas/image-manipulation support for the mask overlay. Requires deliberate discipline to avoid state-management sprawl. |
| **React + JavaScript** | Faster to start. Rejected: the brief requires strong typing (§3), and an untyped client against a medical API means a field rename surfaces as a blank value in a clinical view instead of a build error. |
| **Angular** | Batteries-included and strongly typed. Contradicts the brief; heavier than needed for this surface area. |
| **Server-rendered Thymeleaf in Spring Boot** | One fewer deployable. Rejected: the mask viewer needs rich client-side interaction (zoom, pan, opacity), which is awkward server-rendered, and it would couple UI iteration to backend deploys. |

## Decision

**React + TypeScript, built with Vite.**

Supporting decisions:

- **TypeScript in `strict` mode.** Non-strict TypeScript provides the appearance of type
  safety without the guarantee.
- **API types are hand-maintained against the OpenAPI contract**, in one place
  (`src/types/`), so a contract change breaks compilation at a single point rather than
  silently in many components.
- **No component library adopted by default.** Evaluated only if repeated need appears
  (brief §60). A dependency for its own sake is a maintenance cost.
- **No global state library initially.** Server state is fetched per view; only
  authentication state is genuinely global. Redux/Zustand would be introduced with a
  documented reason, not pre-emptively.

## Reason

React is specified, so the substantive decisions are typing and restraint.

The typing argument is domain-specific: in a clinical UI, a silent `undefined` is worse than
a crash. If the backend renames `confidence` and the client reads `result.confidence`,
untyped code renders an empty field next to a tumour classification — indistinguishable from
"no confidence available". Strict typing converts that into a compile failure.

The restraint argument follows brief §6 and §49: the required behaviours are loading,
skeleton, empty, error, and success states, plus role-based rendering and route protection.
None of that needs a state-management framework, and adding one now would fix an
architecture before its constraints are known.

## Trade-offs

**Accepted:**

- Hand-maintained API types can drift from the backend. Mitigated by keeping them in one
  directory and reviewing them whenever the OpenAPI contract changes. Generated types were
  considered but add a build-time coupling; revisit if drift actually occurs.
- No component library means building form controls, dialogs, and tables. Accepted: it keeps
  the bundle small and the visual language consistent with a clinical tool rather than a
  vendor's default look.
- Client-side rendering means no SEO. Irrelevant — every route is behind authentication.

## Consequences

- All routes except `/login` are protected; unauthenticated access redirects rather than
  rendering a shell.
- Role-based UI hides actions a role cannot perform, but **the server remains the
  authority** — hidden UI is a usability affordance, never an access control.
- The medical disclaimer is rendered persistently, not on a dismissible banner (brief §45).
- UI copy is reviewed against brief §44: no informal phrasing, no certainty claims, and
  "AI-assisted analysis" language throughout.
- Absent, in-progress, and failed analyses use the exact copy in brief §34 rather than
  improvised text, so a missing result never reads as a negative finding.

## Verification

Frontend implementation is **NOT STARTED** (Phase 12). Nothing about this ADR is yet
verified in running code; see `docs/TASKS.md`.
