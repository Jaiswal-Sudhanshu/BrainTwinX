package com.braintwinx.entity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of an asynchronous analysis job (brief section 20).
 *
 * <pre>
 *   QUEUED → RUNNING → SUCCEEDED
 *      │        │
 *      │        └──→ FAILED ──→ QUEUED (retry)
 *      └──→ CANCELLED
 * </pre>
 */
public enum JobStatus {

    /** Awaiting a worker. */
    QUEUED,

    /** A worker holds this job and inference is in progress. */
    RUNNING,

    /** Completed; results persisted. Terminal. */
    SUCCEEDED,

    /** Failed with a recorded error code. Retryable. */
    FAILED,

    /** Abandoned before completion. Terminal. */
    CANCELLED;

    private static final Map<JobStatus, Set<JobStatus>> ALLOWED_TRANSITIONS = Map.of(
            QUEUED,    EnumSet.of(RUNNING, CANCELLED, FAILED),
            RUNNING,   EnumSet.of(SUCCEEDED, FAILED),
            SUCCEEDED, Collections.emptySet(),
            FAILED,    EnumSet.of(QUEUED),
            CANCELLED, Collections.emptySet()
    );

    public boolean canTransitionTo(JobStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Collections.emptySet()).isEmpty();
    }
}
