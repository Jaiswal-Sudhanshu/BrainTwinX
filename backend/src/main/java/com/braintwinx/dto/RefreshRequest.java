package com.braintwinx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A refresh-token exchange request. */
public record RefreshRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 256, message = "must be at most 256 characters")
        String refreshToken) {

    /** Overridden so an accidental log statement cannot print the token. */
    @Override
    public String toString() {
        return "RefreshRequest{refreshToken=[REDACTED]}";
    }
}
