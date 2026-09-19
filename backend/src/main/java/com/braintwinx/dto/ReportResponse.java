package com.braintwinx.dto;

import com.braintwinx.entity.ExplanationStatus;
import java.time.Instant;

/**
 * Public presentation of a generated decision-support clinical report.
 */
public record ReportResponse(
        String publicId,
        String scanPublicId,
        String patientCode,
        ExplanationStatus explanationStatus,
        String explanationProvider,
        String explanationModel,
        long fileSizeBytes,
        String contentSha256,
        Instant createdAt,
        String generatedByUsername
) {}
