package com.braintwinx.entity;

/**
 * Recorded sex of a patient, used only where it carries clinical relevance.
 *
 * <p>{@link #UNKNOWN} is the default rather than a guess: data minimisation
 * (brief section 26) means an absent value is recorded as absent, never inferred.
 */
public enum PatientSex {
    MALE,
    FEMALE,
    OTHER,
    UNKNOWN
}
