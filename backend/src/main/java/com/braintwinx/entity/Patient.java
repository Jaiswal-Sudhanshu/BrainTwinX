package com.braintwinx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A patient record, deliberately data-minimised (brief section 26, ASSUMPTIONS.md A-8).
 *
 * <p>Stores no name, contact details, address, or free-text clinical history. Age context
 * is carried by {@code birthYear} rather than a full date of birth, which is a direct
 * identifier. Every field here exists because a described feature needs it.
 *
 * <p>{@code patientCode} is the only identifier that appears in APIs and URLs.
 */
@Entity
@Table(name = "patients")
public class Patient extends MutableEntity {

    @Column(name = "patient_code", nullable = false, length = 32)
    private String patientCode;

    /** Year only. A full date of birth is not collected. */
    @Column(name = "birth_year")
    private Short birthYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "sex", nullable = false, length = 16)
    private PatientSex sex = PatientSex.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PatientStatus status = PatientStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false, updatable = false)
    private User createdBy;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Patient() {
        // Required by JPA.
    }

    public Patient(String patientCode, Short birthYear, PatientSex sex, User createdBy) {
        this.patientCode = patientCode;
        this.birthYear = birthYear;
        this.sex = sex != null ? sex : PatientSex.UNKNOWN;
        this.createdBy = createdBy;
        this.status = PatientStatus.ACTIVE;
    }

    public String getPatientCode() {
        return patientCode;
    }

    public Short getBirthYear() {
        return birthYear;
    }

    public PatientSex getSex() {
        return sex;
    }

    public PatientStatus getStatus() {
        return status;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setBirthYear(Short birthYear) {
        this.birthYear = birthYear;
    }

    public void setSex(PatientSex sex) {
        this.sex = sex != null ? sex : PatientSex.UNKNOWN;
    }

    /**
     * Soft-archives the record. Idempotent, and keeps {@code status} and
     * {@code archivedAt} in step with the database CHECK constraint that requires them to
     * agree. There is no hard delete: predictions, reports, and audit entries reference
     * this row (ASSUMPTIONS.md A-13).
     */
    public void archive(Instant at) {
        if (this.status != PatientStatus.ARCHIVED) {
            this.status = PatientStatus.ARCHIVED;
            this.archivedAt = at;
        }
    }

    /** Restores an archived record, clearing the timestamp to satisfy the same constraint. */
    public void restore() {
        if (this.status != PatientStatus.ACTIVE) {
            this.status = PatientStatus.ACTIVE;
            this.archivedAt = null;
        }
    }

    public boolean isActive() {
        return status == PatientStatus.ACTIVE;
    }

    /** Carries no patient-identifying data beyond the public-safe code. */
    @Override
    public String toString() {
        return "Patient{patientCode=" + patientCode + ", status=" + status + "}";
    }
}
