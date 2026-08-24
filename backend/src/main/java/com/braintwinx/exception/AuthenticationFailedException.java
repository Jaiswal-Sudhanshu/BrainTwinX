package com.braintwinx.exception;

/**
 * Authentication did not succeed.
 *
 * <p>The client-facing message is always the generic {@code UNAUTHORIZED} text regardless of the
 * true reason, so the response cannot be used to distinguish an unknown username from a wrong
 * password. The specific reason is preserved in {@code internalMessage} for the audit trail and
 * server log only.
 */
public class AuthenticationFailedException extends ApiException {

    private static final long serialVersionUID = 1L;

    public AuthenticationFailedException(String internalMessage) {
        super(ApiErrorCode.UNAUTHORIZED, internalMessage);
    }

    public AuthenticationFailedException(ApiErrorCode errorCode, String internalMessage) {
        super(errorCode, internalMessage);
    }
}
