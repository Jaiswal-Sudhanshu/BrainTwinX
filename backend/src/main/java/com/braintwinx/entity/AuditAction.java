package com.braintwinx.entity;

/**
 * Auditable events (project brief section 27).
 *
 * <p>A closed enumeration rather than a free-text string: it keeps the audit vocabulary
 * consistent, makes queries reliable, and prevents an ad-hoc call site from inventing an
 * action name that later analysis would miss.
 */
public enum AuditAction {

    // --- Authentication ---
    LOGIN,
    LOGOUT,
    LOGIN_FAILED,
    TOKEN_REFRESHED,

    // --- Patients ---
    PATIENT_CREATED,
    PATIENT_UPDATED,
    PATIENT_ARCHIVED,
    PATIENT_VIEWED,

    // --- Scans ---
    SCAN_UPLOADED,
    SCAN_VALIDATION_FAILED,

    // --- Analysis ---
    ANALYSIS_STARTED,
    ANALYSIS_COMPLETED,
    ANALYSIS_FAILED,

    // --- Reports ---
    REPORT_GENERATED,
    REPORT_ACCESSED,
    REPORT_GENERATION_FAILED,

    // --- Explanations ---
    EXPLANATION_GENERATED,
    EXPLANATION_REJECTED,

    // --- Administration ---
    USER_CREATED,
    USER_ROLE_CHANGED,
    USER_DISABLED,
    MODEL_VERSION_REGISTERED,
    MODEL_VERSION_ACTIVATED
}
