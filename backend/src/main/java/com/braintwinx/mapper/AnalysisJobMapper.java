package com.braintwinx.mapper;

import com.braintwinx.dto.AnalysisJobResponse;
import com.braintwinx.entity.AnalysisJob;
import org.springframework.stereotype.Component;

/**
 * Hand-written one-way mapper for {@link AnalysisJob}.
 */
@Component
public class AnalysisJobMapper {

    public AnalysisJobResponse toResponse(AnalysisJob job) {
        return new AnalysisJobResponse(
                job.getPublicId(),
                job.getScan().getPublicId(),
                job.getIdempotencyKey(),
                job.getStatus(),
                job.getProgressPercent(),
                job.getAttemptCount(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getQueuedAt(),
                job.getStartedAt(),
                job.getFinishedAt()
        );
    }
}
