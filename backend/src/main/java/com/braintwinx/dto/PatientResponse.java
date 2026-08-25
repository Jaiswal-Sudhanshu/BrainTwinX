package com.braintwinx.dto;

import com.braintwinx.entity.PatientSex;
import com.braintwinx.entity.PatientStatus;
import java.time.Instant;

/**
 * A patient record as returned by the API.
 *
 * <p><strong>Deliberately absent: the internal database id</strong> (brief sections 8 and 26).
 * {@code patientCode} is the only identifier exposed, so a client cannot infer record counts or
 * enumerate neighbours from a sequential key.
 *
 * <p><strong>Also absent: who created the record.</strong> Returning the creating user would leak
 * the existence and identity of other clinicians to anyone who can read the patient, which is
 * information the caller has no need for. It is recorded in the audit trail instead.
 */
public record PatientResponse(
        String patientCode,
        Short birthYear,
        PatientSex sex,
        PatientStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt) {
}
