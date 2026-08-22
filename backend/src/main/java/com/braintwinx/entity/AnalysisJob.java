package com.braintwinx.entity;

import com.braintwinx.exception.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An asynchronous analysis job (brief sections 20 and 21).
 *
 * <p>MRI inference is too slow to hold an HTTP request open for, so the analyse endpoint
 * creates a job, returns immediately, and the client polls for status. Job state lives in
 * MySQL rather than a broker: it survives a restart and needs no additional operational
 * component (ASSUMPTIONS.md A-9).
 *
 * <p>{@code idempotencyKey} is unique per scan. A repeated analyse request carrying the
 * same key resolves to the existing job instead of starting duplicate inference and
 * writing a second prediction — the duplicate-analysis problem in brief section 21.
 */
@Entity
@Table(name = "analysis_jobs")
public class AnalysisJob extends MutableEntity {

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false, updatable = false)
    private Scan scan;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private JobStatus status = JobStatus.QUEUED;

    @Column(name = "progress_percent", nullable = false)
    private short progressPercent;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_user_id", nullable = false, updatable = false)
    private User requestedBy;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected AnalysisJob() {
        // Required by JPA.
    }

    public AnalysisJob(Scan scan, String idempotencyKey, User requestedBy, Instant queuedAt) {
        this.publicId = UUID.randomUUID().toString();
        this.scan = scan;
        this.idempotencyKey = idempotencyKey;
        this.requestedBy = requestedBy;
        this.queuedAt = queuedAt;
        this.status = JobStatus.QUEUED;
        this.progressPercent = 0;
    }

    public String getPublicId() {
        return publicId;
    }

    public Scan getScan() {
        return scan;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public JobStatus getStatus() {
        return status;
    }

    public short getProgressPercent() {
        return progressPercent;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public User getRequestedBy() {
        return requestedBy;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    /** Claims the job for a worker. */
    public void start(Instant at) {
        requireTransition(JobStatus.RUNNING);
        this.status = JobStatus.RUNNING;
        this.startedAt = at;
        this.attemptCount++;
        this.errorCode = null;
        this.errorMessage = null;
    }

    /**
     * Reports progress. Clamped to 0–100 rather than trusting a caller, because the
     * database CHECK constraint would otherwise reject the write and lose the update.
     */
    public void updateProgress(int percent) {
        this.progressPercent = (short) Math.clamp(percent, 0, 100);
    }

    public void succeed(Instant at) {
        requireTransition(JobStatus.SUCCEEDED);
        this.status = JobStatus.SUCCEEDED;
        this.finishedAt = at;
        this.progressPercent = 100;
        this.errorCode = null;
        this.errorMessage = null;
    }

    /**
     * Fails the job with a mandatory error code, matching the schema constraint that a
     * failed job must record why.
     *
     * @throws IllegalArgumentException if {@code errorCode} is absent
     */
    public void fail(String errorCode, String errorMessage, Instant at) {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("An error code is required when failing a job");
        }
        requireTransition(JobStatus.FAILED);
        this.status = JobStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.finishedAt = at;
    }

    /** Returns a failed job to the queue. Clears the previous error per the schema constraint. */
    public void requeue(Instant at) {
        requireTransition(JobStatus.QUEUED);
        this.status = JobStatus.QUEUED;
        this.queuedAt = at;
        this.startedAt = null;
        this.finishedAt = null;
        this.progressPercent = 0;
        this.errorCode = null;
        this.errorMessage = null;
    }

    public void cancel(Instant at) {
        requireTransition(JobStatus.CANCELLED);
        this.status = JobStatus.CANCELLED;
        this.finishedAt = at;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    private void requireTransition(JobStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException("AnalysisJob", status.name(), target.name());
        }
    }

    @Override
    public String toString() {
        return "AnalysisJob{publicId=" + publicId + ", status=" + status
                + ", progress=" + progressPercent + "}";
    }
}
