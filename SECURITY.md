# BrainTwinX Security & Privacy Policy

BrainTwinX is an AI-assisted clinical decision support system designed for neuro-oncology imaging. We take patient safety, data privacy, and software security with the utmost seriousness.

---

## 1. Security & Privacy Architecture

The comprehensive privacy and security documentation package is located in [`docs/privacy-and-security/`](docs/privacy-and-security/):

1. [**01-SECURITY-ARCHITECTURE.md**](docs/privacy-and-security/01-SECURITY-ARCHITECTURE.md): Threat modeling (STRIDE), network boundary isolation, dual-layer IDOR protection, JWT token hashing, and audit logging.
2. [**02-HIPAA-GDPR-COMPLIANCE.md**](docs/privacy-and-security/02-HIPAA-GDPR-COMPLIANCE.md): PHI minimization, Safe Harbor pseudonymization, GDPR Article 9 special category data safeguards, and data retention rules.
3. [**03-NEURO-AI-SAFETY-FRAMEWORK.md**](docs/privacy-and-security/03-NEURO-AI-SAFETY-FRAMEWORK.md): SaMD regulatory alignment, the 10 Clinical Safety Prohibitions, multi-modal LLM isolation, and fail-closed safety.
4. [**04-DATA-HANDLING-AND-INCIDENT-RESPONSE.md**](docs/privacy-and-security/04-DATA-HANDLING-AND-INCIDENT-RESPONSE.md): Path traversal prevention, SHA-256 integrity digests, container hardening, and NIST SP 800-61 incident response protocol.

---

## 2. Reporting a Vulnerability

If you discover a security vulnerability or clinical AI safety defect in BrainTwinX, please report it responsibly:

- **Email:** `security@braintwinx.org` (or open an advisory on GitHub Security Advisories)
- **Encryption:** Please use our PGP key for sensitive disclosures.
- **Response Timeline:** 
  - Acknowledgment within **24 hours**.
  - Initial assessment and triage within **48 hours**.
  - Status updates every **3 business days** until resolution.

Please **do not** report security vulnerabilities through public GitHub issues.
