package com.braintwinx.entity;

/**
 * Outcome of a longitudinal growth analysis (brief section 13).
 *
 * <p>{@link #INSUFFICIENT_HISTORY} is a legitimate, recorded result — not an error and
 * not a reason to fabricate a trend. The database enforces that no forecast, trend
 * direction, or model attribution may accompany it.
 */
public enum GrowthAnalysisStatus {

    /** A model produced a trend estimate. */
    COMPLETED,

    /**
     * Too few observations, or too short a time span, to support any trend statement.
     * No forecast is produced and none is stored.
     */
    INSUFFICIENT_HISTORY,

    /** Sufficient history existed but the analysis itself failed. */
    FAILED
}
