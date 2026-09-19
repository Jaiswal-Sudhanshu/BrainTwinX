package com.braintwinx.dto;

import com.braintwinx.entity.ExplanationStatus;
import java.time.Instant;

/**
 * Public representation of an AI-generated decision-support explanation.
 */
public record ExplanationResponse(
        String scanPublicId,
        String patientCode,
        ExplanationStatus status,
        String explanationText,
        String provider,
        String model,
        String rejectionReason,
        Instant generatedAt
) {}
