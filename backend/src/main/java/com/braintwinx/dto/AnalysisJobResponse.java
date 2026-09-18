package com.braintwinx.dto;

import com.braintwinx.entity.JobStatus;
import java.time.Instant;

/**
 * Public representation of an asynchronous MRI analysis job.
 */
public record AnalysisJobResponse(
        String publicId,
        String scanPublicId,
        String idempotencyKey,
        JobStatus status,
        short progressPercent,
        int attemptCount,
        String errorCode,
        String errorMessage,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt
) {}
