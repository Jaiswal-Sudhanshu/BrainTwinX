# BrainTwinX — HIPAA & GDPR Compliance Specification

**Classification:** Healthcare Privacy & Data Protection Engineering Standard  
**Regulatory Standards:** Health Insurance Portability and Accountability Act (HIPAA) Privacy and Security Rules (45 CFR Part 160 and Part 164), General Data Protection Regulation (GDPR / Regulation (EU) 2016/679)

---

## 1. Principles of Medical Data Minimization

BrainTwinX strictly enforces the principle of **data minimization** (GDPR Art. 5(1)(c) and HIPAA § 164.502(b) Minimum Necessary requirement). The system collects and stores only the minimum demographic and clinical variables necessary to perform decision support.

### Explicitly Excluded Identifiers
Under the **HIPAA Safe Harbor De-Identification Standard (§ 164.514(b)(2))**, 18 categories of direct identifiers are recognized. BrainTwinX completely excludes:
- Names, initials, or geographic subdivisions smaller than a state.
- Exact dates of birth (only 4-digit `birthYear` is recorded; e.g. 1982).
- Social Security Numbers (SSN), Medical Record Numbers (MRN), Health Plan IDs, Account Numbers.
- Telephone numbers, email addresses, device serial numbers, or biometric identifiers.
- Full-face photographic images or 3D surface reconstructions.

---

## 2. Patient Pseudonymization Architecture

```
Clinical EHR / Hospital PACS
          │
          ▼ [De-Identification Gateway]
  Patient Name / MRN stripped
          │
          ▼
BrainTwinX Database:
  ├── patient_code: "PAT-A4F92B10"   (Internal random pseudonym)
  ├── birth_year:   1984             (4-digit year only)
  ├── sex:          FEMALE           (Biological sex for volumetric baselines)
  └── status:       ACTIVE           (ACTIVE | ARCHIVED)
```

1. **Pseudonymous Patient Codes:**
   - Patients are identified solely by a site-scoped `patientCode` (e.g. `PAT-7819ACDE`).
   - The mapping between the physical patient and the `patientCode` resides strictly in the hospital's sovereign electronic health record (EHR), never within BrainTwinX.
2. **Scan Image De-Identification:**
   - Uploaded PNG/JPEG MRI slices must have all DICOM header tags containing patient names, accession numbers, and institution names scrubbed prior to ingestion.
   - BrainTwinX performs automated binary validation to ensure no EXIF or embedded text tags leak identity into filesystem storage.

---

## 3. GDPR Special Category Data Protection (Article 9)

Health data constitutes special category personal data under GDPR Art. 9.

| GDPR Requirement | BrainTwinX Technical Implementation |
|---|---|
| **Art. 9(2)(j) Scientific / Clinical Research** | Pseudonymization is mandatory; researchers can access anonymized image features and model telemetry without access to clinician notes. |
| **Art. 25 Data Protection by Design** | No raw images are sent to external LLMs; LLM inputs are allow-listed mathematical structs. |
| **Art. 32 Security of Processing** | AES-256 encryption at rest for filesystem scan artefacts; TLS 1.3 encryption in transit; BCrypt hash for passwords; SHA-256 for refresh tokens. |
| **Art. 17 Right to Erasure ("Right to be Forgotten")** | Soft archiving with explicit audit trails. Complete erasure API is supported for research cohorts while respecting statutory clinical retention periods. |

---

## 4. Retention & Archiving State Consistency

Patients and scans support structured archiving. To prevent data corruption, MySQL CHECK constraints enforce state consistency:
```sql
CONSTRAINT ck_patients_archived_consistency
    CHECK ((status = 'ARCHIVED' AND archived_at IS NOT NULL)
        OR (status <> 'ARCHIVED' AND archived_at IS NULL))
```
- An active patient cannot carry a stale archived timestamp.
- An archived patient cannot have new scans uploaded or analyses triggered, preventing orphaned records.
- Deletion operations cascade properly to child segmentation masks and cached artefacts without leaving dangling file pointers.

---

## 5. Breach Notification & Audit Trail Preservation

In compliance with 45 CFR §§ 164.400–414 and GDPR Art. 33–34:
- All access events to patient records, MRI scans, segmentation masks, and generated diagnostic reports are logged immutably in `audit_logs`.
- In the event of unauthorized access or anomalous activity (e.g. out-of-scope IDOR access attempts), security alerts are emitted immediately to SIEM listeners.
