# BrainTwinX — Data Handling & Incident Response Protocol

**Classification:** Standard Operating Procedure (SOP) & Technical Data Controls  
**Compliance:** NIST SP 800-61 (Computer Security Incident Handling Guide), HIPAA Security Rule § 164.308(a)(6)

---

## 1. File Lifecycle & Storage Security

Uploaded MRI scans, segmentation masks, and generated PDF reports are handled with defensive filesystem controls:

```
Upload -> [ImageValidationService] -> [FilesystemStorageService] -> Storage Volume
              │                               │
              ├─ Magic Byte Check             ├─ Path Traversal Sanitization
              ├─ Dimension / Bomb Check       ├─ Server-Generated UUID Key
              └─ SHA-256 Digest Calculation   └─ Posix 0600 / ACL Enforcement
```

### Path Traversal Defenses
User-supplied filenames (e.g. `patient_mri.png`) are **never** used to construct filesystem paths. The `FilesystemStorageService` enforces:
1. **Server-Generated UUIDs:** Every stored object is assigned an opaque key: `scans/{uuid}.png`, `masks/{uuid}.png`, or `reports/{uuid}.pdf`.
2. **Strict Lexical Sanitization:** Rejection of `../`, `..\`, absolute paths (`/`, `C:\`), NUL bytes (`\0`), and Windows-reserved device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1`, etc.).
3. **Canonical Path Assertion:** Before opening any file stream, the canonical path is verified to reside strictly within the configured root directory:
   ```java
   Path resolved = rootDir.resolve(storageKey).normalize().toAbsolutePath();
   if (!resolved.startsWith(rootDir.toAbsolutePath())) {
       log.error("Path traversal attempt detected with storageKey: {}", storageKey);
       throw new ApiException(ApiErrorCode.FORBIDDEN, "Invalid storage key");
   }
   ```

---

## 2. Integrity Validation

Every scan uploaded to BrainTwinX is digested using SHA-256 upon stream intake:
- The SHA-256 digest is stored in `scans.content_sha256`.
- **Duplicate Upload Guard:** The database unique index on `(patient_id, content_sha256)` rejects duplicate uploads of the exact same scan for a patient, preventing redundant processing.
- When generating reports or computing inference, the SHA-256 hash is verified against the stored bytes to detect filesystem corruption or bit-rot.

---

## 3. Container & Runtime Hardening

In production deployment (Phase 15):
1. **Non-Root Execution:** Backend and AI-service containers execute as dedicated unprivileged system users (`braintwinx:braintwinx`, UID 10001).
2. **Read-Only Root Filesystems:** Container root filesystems are mounted read-only, with dedicated volume mounts for storage caches.
3. **No Interactive Shells:** Base images strip out debug shells, package managers, and development utilities in release images.

---

## 4. Security Incident Response Protocol

In the event of suspected unauthorized access, credential compromise, or abnormal data access patterns:

### Phase 1: Detection & Triage
- Identify anomalous behavior via `audit_logs` (e.g. repeated 404 IDOR alerts from an authenticated user, spikes in failed authentications).
- Categorize incident severity (Low: probing; Medium: authenticated out-of-scope access; High: data extraction or key compromise).

### Phase 2: Containment
- Revoke all active sessions for suspect user accounts by invalidating `refresh_tokens` and rotating user credentials.
- Block originating IP addresses at the reverse proxy / firewall tier.
- If an internal API key or JWT secret is compromised, rotate environment secrets immediately and restart the services.

### Phase 3: Investigation & Recovery
- Query `audit_logs` using the `correlation_id` to trace the exact scope of records touched.
- Validate that MySQL CHECK constraints prevented any unauthorized schema mutations or data fabrication.

### Phase 4: Notification
- If unencrypted PHI was accessed without authorization, notify the designated Data Protection Officer (DPO) and affected clinical sites in compliance with statutory breach notification timelines (HIPAA: within 60 days; GDPR: within 72 hours).
