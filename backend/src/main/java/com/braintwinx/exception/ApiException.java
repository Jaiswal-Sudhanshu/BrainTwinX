package com.braintwinx.exception;

/**
 * Base class for exceptions that map to a known {@link ApiErrorCode}.
 *
 * <p>Carrying the code on the exception means the HTTP status and client-facing message are
 * decided by the domain, not guessed by the exception handler from the exception type. Anything
 * that does not extend this becomes {@code INTERNAL_ERROR} with no detail leaked — a safe
 * default for genuinely unexpected failures.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ApiErrorCode errorCode;

    public ApiException(ApiErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    /**
     * @param internalMessage detail for the server log only. It is NOT returned to the client,
     *                        so it may name internal specifics that aid diagnosis.
     */
    public ApiException(ApiErrorCode errorCode, String internalMessage) {
        super(internalMessage);
        this.errorCode = errorCode;
    }

    public ApiException(ApiErrorCode errorCode, String internalMessage, Throwable cause) {
        super(internalMessage, cause);
        this.errorCode = errorCode;
    }

    public ApiErrorCode getErrorCode() {
        return errorCode;
    }
}
