package com.braintwinx.entity;

/**
 * Availability of a registered model version.
 *
 * <p>Exactly one version per {@link ModelType} should be {@code ACTIVE} at a time.
 * Superseded versions are retained rather than deleted, so an existing prediction can
 * still be attributed to the model that produced it (brief section 22).
 */
public enum ModelStatus {

    /** Currently serving inference. */
    ACTIVE,

    /** Registered but not serving. */
    INACTIVE,

    /** Retained for provenance only; must not be selected for new inference. */
    DEPRECATED
}
