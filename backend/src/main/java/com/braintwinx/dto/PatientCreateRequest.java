package com.braintwinx.dto;

import com.braintwinx.entity.PatientSex;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to create a patient record.
 *
 * <p>Only the three fields the platform actually needs are accepted. There is deliberately no
 * name, contact detail, address, or free-text history field: data minimisation (brief section 26,
 * ASSUMPTIONS.md A-8) is enforced by the DTO having nowhere to put them, not by a convention that
 * a future contributor might not notice.
 *
 * <p>{@code patientCode} is supplied by the caller because brief section 8 requires duplicate
 * detection on it, which implies the caller owns the coding scheme. The format is constrained so a
 * code cannot smuggle path separators, whitespace, or control characters into somewhere that
 * matters later.
 *
 * @param birthYear year only — a full date of birth is a direct identifier and is not collected
 */
public record PatientCreateRequest(

        @NotBlank(message = "must not be blank")
        @Size(max = 32, message = "must be at most 32 characters")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*",
                message = "must contain only letters, digits, hyphens and underscores, "
                        + "and must start with a letter or digit")
        String patientCode,

        @Min(value = 1900, message = "must be 1900 or later")
        @Max(value = 2200, message = "must be 2200 or earlier")
        Short birthYear,

        PatientSex sex) {
}
