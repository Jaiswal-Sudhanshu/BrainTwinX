package com.braintwinx.entity;

/**
 * Provenance of the AI-generated explanation attached to a report.
 *
 * <p>Recorded explicitly so a reader can always tell why an explanation is absent.
 * A blank section could otherwise be misread as a negative clinical finding.
 */
public enum ExplanationStatus {

    /** Generated and passed safety validation. */
    INCLUDED,

    /** No provider configured, or the provider was unreachable. Fails closed. */
    UNAVAILABLE,

    /**
     * Generated but rejected by the safety validator — for example it introduced a
     * measurement or finding absent from the structured model output. The text is
     * discarded rather than shown.
     */
    REJECTED
}
