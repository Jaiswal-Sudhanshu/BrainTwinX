package com.braintwinx.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single error envelope returned by every endpoint (project brief section 19).
 *
 * <p>Shape is fixed so clients can rely on it:
 *
 * <pre>
 * {
 *   "timestamp": "2026-08-22T10:15:30Z",
 *   "status": 400,
 *   "code": "INVALID_REQUEST",
 *   "message": "The request is invalid.",
 *   "traceId": "c0ffee...",
 *   "fieldErrors": [ { "field": "patientCode", "message": "must not be blank" } ]
 * }
 * </pre>
 *
 * <p><strong>Deliberately absent: any stack trace, exception class name, SQL fragment, or
 * internal identifier.</strong> {@code traceId} is the only handle a client gets, and it is what
 * correlates the response with the full server-side detail in the logs and in
 * {@code audit_logs.trace_id}. That keeps the response useful for support without turning error
 * handling into an information-disclosure channel.
 *
 * @param fieldErrors present only for validation failures; omitted from JSON when null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String traceId,
        List<FieldError> fieldErrors,
        Map<String, Object> details) {

    /** A single rejected field. Carries the field name and reason, never the rejected value. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldError(String field, String message) {
    }

    public static ApiError of(ApiErrorCode code, String traceId) {
        return new ApiError(Instant.now(), code.status().value(), code.name(),
                code.defaultMessage(), traceId, null, null);
    }

    /**
     * Builds an error with a caller-supplied message.
     *
     * <p>Callers are responsible for ensuring the message is client-safe: no internals, no
     * patient data. Prefer {@link #of(ApiErrorCode, String)} unless a specific safe message
     * genuinely helps the caller correct their request.
     */
    public static ApiError of(ApiErrorCode code, String traceId, String safeMessage) {
        return new ApiError(Instant.now(), code.status().value(), code.name(),
                safeMessage, traceId, null, null);
    }

    public static ApiError validation(String traceId, List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), ApiErrorCode.INVALID_REQUEST.status().value(),
                ApiErrorCode.INVALID_REQUEST.name(),
                ApiErrorCode.INVALID_REQUEST.defaultMessage(), traceId, fieldErrors, null);
    }

    public static ApiError withDetails(ApiErrorCode code, String traceId, Map<String, Object> details) {
        return new ApiError(Instant.now(), code.status().value(), code.name(),
                code.defaultMessage(), traceId, null, details);
    }
}
