package com.braintwinx.entity;

/**
 * Patient record lifecycle.
 *
 * <p>There is no hard delete. Archiving is a soft operation because predictions,
 * reports, and audit entries reference the patient, and brief section 27 requires the
 * audit trail be preserved (ASSUMPTIONS.md A-13).
 */
public enum PatientStatus {

    /** Included in default listings and eligible for new scans. */
    ACTIVE,

    /** Retained and auditable, excluded from default listings, no new scans. */
    ARCHIVED
}
