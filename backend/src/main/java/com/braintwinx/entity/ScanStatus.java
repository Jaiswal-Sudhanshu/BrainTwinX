package com.braintwinx.entity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of an MRI scan, per project brief section 52.
 *
 * <p>The legal transition graph lives here — in one authoritative place — rather than
 * being scattered across services. Brief section 52 requires that arbitrary status
 * changes be impossible; centralising the rule is what makes that enforceable and
 * testable.
 *
 * <pre>
 *   UPLOADED → VALIDATING → VALIDATED → QUEUED → PROCESSING → COMPLETED
 *                   │            │        │           │
 *                   └────────────┴────────┴───────────┴──→ FAILED
 *                                                            │
 *                                                            └──→ QUEUED (retry)
 * </pre>
 *
 * <p>{@code COMPLETED} is terminal: a completed analysis is never mutated. Re-analysis
 * creates a new {@link AnalysisJob} and appends a new prediction, preserving the record
 * that any already-issued report was based on.
 */
public enum ScanStatus {

    /** Stored and hashed, not yet validated. */
    UPLOADED,

    /** Format, signature, size, and decodability checks in progress. */
    VALIDATING,

    /** Passed validation; eligible for analysis. */
    VALIDATED,

    /** An analysis job exists and is awaiting a worker. */
    QUEUED,

    /** A worker is running inference. */
    PROCESSING,

    /** Analysis finished and results are persisted. Terminal. */
    COMPLETED,

    /** A stage failed. Carries a failure code; retryable back to QUEUED. */
    FAILED;

    private static final Map<ScanStatus, Set<ScanStatus>> ALLOWED_TRANSITIONS = Map.of(
            UPLOADED,   EnumSet.of(VALIDATING, FAILED),
            VALIDATING, EnumSet.of(VALIDATED, FAILED),
            VALIDATED,  EnumSet.of(QUEUED, FAILED),
            QUEUED,     EnumSet.of(PROCESSING, FAILED),
            PROCESSING, EnumSet.of(COMPLETED, FAILED),
            COMPLETED,  Collections.emptySet(),
            // Retry path only. A failed scan cannot jump straight to COMPLETED.
            FAILED,     EnumSet.of(QUEUED)
    );

    /** @return {@code true} if moving from this status to {@code target} is permitted. */
    public boolean canTransitionTo(ScanStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    /** @return the statuses reachable in one step from this one. */
    public Set<ScanStatus> allowedTargets() {
        return Collections.unmodifiableSet(
                ALLOWED_TRANSITIONS.getOrDefault(this, Collections.emptySet()));
    }

    /** @return {@code true} if no further transition is possible. */
    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Collections.emptySet()).isEmpty();
    }

    /** @return {@code true} if analysis work is outstanding for this scan. */
    public boolean isInFlight() {
        return this == VALIDATING || this == VALIDATED || this == QUEUED || this == PROCESSING;
    }
}
