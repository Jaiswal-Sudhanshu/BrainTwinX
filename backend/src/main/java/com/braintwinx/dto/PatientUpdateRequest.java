package com.braintwinx.dto;

import com.braintwinx.entity.PatientSex;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Request to update a patient record.
 *
 * <p>{@code patientCode} is intentionally absent and therefore immutable. It is the identifier
 * every scan, prediction, report, and audit entry is correlated by, so allowing it to change would
 * silently break the link between a patient and their own history.
 *
 * <p>{@code status} is also absent: archiving is a distinct, audited operation with its own
 * endpoint rather than a field a generic update could flip.
 */
public record PatientUpdateRequest(

        @Min(value = 1900, message = "must be 1900 or later")
        @Max(value = 2200, message = "must be 2200 or earlier")
        Short birthYear,

        PatientSex sex) {
}
