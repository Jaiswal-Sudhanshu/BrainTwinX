# ADR-006: JWT with rotating refresh tokens for authentication

**Status:** Accepted
**Date:** 2026-08-24
**Deciders:** Engineering owner

---

## Context

BrainTwinX needs authentication for a React SPA talking to a Spring Boot API, with three roles
(ADMIN / DOCTOR / RESEARCHER) and an audit requirement covering login and logout. The brief
specifies JWT (§7).

## Problem

How should sessions be represented, and how should the trade-off between statelessness and
revocability be resolved for an application handling medical data?

## Options considered

| Option | Assessment |
|---|---|
| **Server-side sessions (cookie + session store)** | Trivially revocable and battle-tested. But it requires shared session state across API instances (or sticky sessions), reintroduces CSRF, and contradicts an explicit brief requirement. |
| **Long-lived JWT only** | Simplest to implement. **Rejected outright:** a stateless token cannot be revoked, so a stolen token remains valid for its full lifetime. For a system holding patient data that is not an acceptable failure mode. |
| **Short-lived JWT + opaque rotating refresh token** *(chosen)* | Access checks stay stateless and cheap; revocation is possible because refresh state is server-side. Costs one table and rotation logic. |
| **JWT + a denylist of revoked access tokens** | Revocable, but every request then reads shared state — which forfeits the statelessness that motivated JWT, while adding a cache to operate. |

## Decision

**Short-lived HS256 access JWTs (15 min default) plus opaque, rotating, server-stored refresh
tokens (7 days default).**

Specifics that matter more than the headline:

- **Refresh tokens are NOT JWTs.** They are 256 bits from a `SecureRandom`, and only a
  **SHA-256 hash** is persisted. Revocation requires server-side state, so making them
  self-contained would defeat their purpose; storing them raw would make a database disclosure
  a session-hijacking kit.
- **Rotation on every exchange**, with the consumed token linked to its successor via
  `replaced_by`. Reuse of a consumed token is therefore *detectable*.
- **Reuse revokes the entire token family** for that user. Reuse means a bug or theft; ending
  every session is the safe interpretation.
- **HS256, fixed at both issue and verification.** The parser is bound to the key and the
  algorithm, so `alg:none` and algorithm-confusion attacks cannot succeed.
- **Issuer is verified, not merely set** — otherwise a validly signed token from another system
  sharing the key would be accepted.
- **Subject is the opaque `publicId`**, never the sequential database id (§26).
- **A `typ` claim distinguishes access from refresh tokens**, so neither can be replayed as the
  other.
- **The signing secret has no default and is validated at startup**: under 256 bits, or matching
  a placeholder pattern, aborts the boot.

## Reason

The decisive question was **revocability versus statelessness**, and the answer is domain-driven
rather than aesthetic: in a medical application, "we cannot revoke a stolen credential until it
expires" is not a defensible position. Splitting the two concerns keeps per-request authorisation
stateless — which is what allows API instances to scale without shared session state — while
confining revocation to the far less frequent refresh path, where a database read costs nothing.

A 15-minute access TTL is the honest consequence: access tokens genuinely cannot be revoked, so
their blast radius is bounded by time instead. This is documented in `AuthController` rather than
glossed over, because a client integrator must understand it.

Placeholder-secret rejection deserves its own note: copying `.env.example` verbatim is the single
most likely path by which a weak signing key reaches a real deployment, and a length check alone
would not catch it.

## Trade-offs

**Accepted:**

- An access token remains valid until expiry even after logout. Mitigated by the short TTL and
  documented explicitly.
- Rotation adds a write per refresh. Negligible at this frequency.
- HS256 is symmetric, so any service verifying tokens can also mint them. Acceptable while a
  single service issues and verifies; moving to RS256/ES256 would be required before a third
  party needed verify-only access. Recorded as a future trigger rather than pre-built.
- A `refresh_tokens` table to maintain and prune. `deleteExpiredBefore` exists for housekeeping.

**Explicitly rejected:** storing tokens in `localStorage` is a frontend decision deferred to
Phase 12, where the XSS trade-off against cookie-plus-CSRF will be decided on its own merits.

## Consequences

- CSRF protection is disabled, and that is **correct only because** no cookie carries the
  credential — the browser never attaches a bearer token automatically. The comment in
  `SecurityConfig` says so, so that anyone introducing a cookie-based flow re-enables it.
- Logout revokes refresh tokens and is idempotent.
- Password change must revoke all refresh tokens (`revokeAllForUser`), or a stolen token would
  outlive the credential it was issued against.
- Every authentication outcome is audited, including failures with a closed-vocabulary reason,
  while the HTTP response stays uniform.

## Verification

- 23 unit tests in `JwtServiceTest` covering round-trip, expiry-vs-invalid distinction, wrong
  key, `alg:none`, wrong issuer, wrong token type, unknown role, malformed input, refresh-token
  uniqueness and hashing, and every configuration-validation rule.
- `SecurityFilterChainIT` covers the wired chain against real MySQL.
- Current counts: [`TASKS.md`](../TASKS.md) verification log.
