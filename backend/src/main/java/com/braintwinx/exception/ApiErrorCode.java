package com.braintwinx.exception;

import org.springframework.http.HttpStatus;

/**
 * Closed set of machine-readable API error codes (project brief section 19).
 *
 * <p>A closed enumeration rather than free-form strings: clients can branch on a stable code,
 * and a call site cannot invent a variant that consumers do not handle.
 *
 * <p>Each code carries the HTTP status it maps to, so status selection lives in one place
 * instead of being decided ad hoc at each throw site.
 *
 * <p><strong>Message discipline.</strong> The messages associated with these codes are
 * deliberately generic. They must never contain a stack trace, SQL, an internal identifier, a
 * file path, or patient-identifying data (brief sections 19 and 26). Detail belongs in the
 * server log, correlated by {@code traceId}.
 */
public enum ApiErrorCode {

    // --- Request validation ---
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "The request is invalid."),
    INVALID_FILE(HttpStatus.BAD_REQUEST, "The uploaded file is invalid."),
    UNSUPPORTED_MRI_FORMAT(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The MRI format is not supported."),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The uploaded file exceeds the permitted size."),

    // --- Authentication and authorisation ---
    /**
     * Deliberately identical for an unknown username and a wrong password, so the endpoint is
     * not a user-enumeration oracle.
     */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required or has failed."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "The authentication token has expired."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "The authentication token is not valid."),
    ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED, "The account is temporarily locked."),
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "The account is disabled."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to perform this action."),

    // --- Resources ---
    PATIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "The patient was not found."),
    SCAN_NOT_FOUND(HttpStatus.NOT_FOUND, "The scan was not found."),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "The report was not found."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "A resource with that identifier already exists."),

    // --- State ---
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "The requested state change is not permitted."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The resource was modified concurrently. Retry the request."),

    // --- Analysis and AI ---
    ANALYSIS_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "The analysis could not be completed."),
    AI_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The analysis service is unavailable."),
    MODEL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "No model is available to serve this request."),
    /**
     * Not an error in the pejorative sense: a legitimate, recorded outcome meaning the system
     * declined to estimate a trend rather than fabricating one (brief section 13).
     */
    INSUFFICIENT_HISTORY(HttpStatus.UNPROCESSABLE_CONTENT,
            "There is insufficient history to produce a trend estimate."),

    // --- Reports ---
    REPORT_GENERATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "The report could not be generated."),

    // --- Infrastructure ---
    STORAGE_FAILURE(HttpStatus.SERVICE_UNAVAILABLE, "Storage is currently unavailable."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later."),
    /** Catch-all. The client is told nothing beyond the traceId. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

    private final HttpStatus status;
    private final String defaultMessage;

    ApiErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
