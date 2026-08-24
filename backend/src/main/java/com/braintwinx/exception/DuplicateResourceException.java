package com.braintwinx.exception;

/** A uniqueness constraint would be violated by the request. */
public class DuplicateResourceException extends ApiException {

    private static final long serialVersionUID = 1L;

    public DuplicateResourceException(String internalMessage) {
        super(ApiErrorCode.DUPLICATE_RESOURCE, internalMessage);
    }
}
