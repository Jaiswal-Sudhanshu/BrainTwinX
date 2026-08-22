package com.braintwinx.repository;

import com.braintwinx.entity.AnalysisJob;
import com.braintwinx.entity.JobStatus;
import com.braintwinx.entity.Scan;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link AnalysisJob}.
 */
public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    Optional<AnalysisJob> findByPublicId(String publicId);

    /**
     * Idempotency lookup (brief section 21). A repeated analyse request carrying the same
     * key resolves to the existing job rather than starting duplicate inference.
     */
    Optional<AnalysisJob> findByScanAndIdempotencyKey(Scan scan, String idempotencyKey);

    /** Most recent job for a scan, for status reporting. */
    Optional<AnalysisJob> findFirstByScanOrderByCreatedAtDesc(Scan scan);

    /**
     * Oldest queued jobs first, so work is drained fairly rather than starving an early
     * request. Paginated to bound how much a single worker poll claims.
     */
    @Query("select j from AnalysisJob j where j.status = :status order by j.queuedAt asc")
    List<AnalysisJob> findOldestByStatus(@Param("status") JobStatus status, Pageable pageable);

    /**
     * Detects jobs stuck in {@code RUNNING}, for example after a worker crashed. Used by a
     * reaper so a scan is not left permanently mid-flight — brief section 39 requires every
     * failure to reach a predictable state.
     */
    @Query("""
            select j from AnalysisJob j
             where j.status = com.braintwinx.entity.JobStatus.RUNNING
               and j.startedAt < :staleBefore
            """)
    List<AnalysisJob> findStaleRunning(@Param("staleBefore") java.time.Instant staleBefore);

    long countByStatus(JobStatus status);
}
