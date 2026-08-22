package com.braintwinx.repository;

import com.braintwinx.entity.Patient;
import com.braintwinx.entity.PatientStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Patient}.
 *
 * <p>All external lookups are by {@code patientCode}, never by the internal sequential
 * key, which is never exposed (brief sections 8 and 26).
 *
 * <p>Listings are always paginated. An unbounded {@code findAll} over a patient table is
 * both a performance hazard and a bulk-disclosure hazard, so no such method is offered
 * here beyond the inherited one, which services must not use for listing endpoints.
 */
public interface PatientRepository extends JpaRepository<Patient, Long> {

    Optional<Patient> findByPatientCode(String patientCode);

    boolean existsByPatientCode(String patientCode);

    /** Default listing: filtered by status so archived records are excluded by choice. */
    Page<Patient> findByStatus(PatientStatus status, Pageable pageable);
}
