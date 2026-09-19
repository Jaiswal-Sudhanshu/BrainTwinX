package com.braintwinx.explanation;

import com.braintwinx.entity.ExplanationStatus;
import java.time.Instant;

/**
 * Encapsulates the outcome of an explanation generation attempt.
 *
 * <p>Fail-closed principle: When an explanation fails generation or safety validation,
 * the status is set to {@code UNAVAILABLE} or {@code REJECTED}, and any unverified text
 * is discarded rather than retained.
 */
public record ExplanationResult(
        ExplanationStatus status,
        String explanationText,
        String provider,
        String model,
        String rejectionReason,
        Instant generatedAt
) {
    public ExplanationResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
    }

    public static ExplanationResult included(String text, String provider, String model) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be null or blank for INCLUDED status");
        }
        return new ExplanationResult(
                ExplanationStatus.INCLUDED,
                text,
                provider,
                model,
                null,
                Instant.now()
        );
    }

    public static ExplanationResult unavailable(String reason, String provider) {
        return new ExplanationResult(
                ExplanationStatus.UNAVAILABLE,
                null,
                provider,
                null,
                reason,
                Instant.now()
        );
    }

    public static ExplanationResult rejected(String reason, String provider, String model) {
        return new ExplanationResult(
                ExplanationStatus.REJECTED,
                null,
                provider,
                model,
                reason,
                Instant.now()
        );
    }
}
