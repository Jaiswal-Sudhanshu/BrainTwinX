package com.braintwinx.repository;

import com.braintwinx.entity.Patient;
import com.braintwinx.entity.PatientStatus;
import com.braintwinx.entity.User;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Patient}.
 *
 * <p>All external lookups are by {@code patientCode}, never by the internal sequential key, which
 * is never exposed (brief sections 8 and 26).
 *
 * <p>Listings are always paginated. An unbounded {@code findAll} over a patient table is both a
 * performance hazard and a bulk-disclosure hazard, so no such method is offered here beyond the
 * inherited one, which services must not use for listing endpoints.
 */
public interface PatientRepository extends JpaRepository<Patient, Long> {

    /**
     * Loads a patient together with its creating user.
     *
     * <p>The creating user is fetched eagerly because every access-scope check needs it. Leaving it
     * lazy would issue a second query on <em>every</em> patient lookup — the N+1 pattern brief
     * section 16 warns against — and would do so on the security-critical path, which is the worst
     * place for a hidden query.
     */
    @EntityGraph(attributePaths = "createdBy")
    Optional<Patient> findByPatientCode(String patientCode);

    boolean existsByPatientCode(String patientCode);

    /** Default listing for callers with unrestricted scope: filtered by status. */
    @EntityGraph(attributePaths = "createdBy")
    Page<Patient> findByStatus(PatientStatus status, Pageable pageable);

    /**
     * Listing scoped to one creating user.
     *
     * <p>Enforces the caseload boundary in the query rather than by filtering an unscoped result in
     * memory. Filtering after the fact would still transfer other clinicians' records out of the
     * database, and a paging bug would then leak them.
     */
    @EntityGraph(attributePaths = "createdBy")
    Page<Patient> findByCreatedByAndStatus(User createdBy, PatientStatus status, Pageable pageable);

    long countByStatus(PatientStatus status);

    long countByCreatedByAndStatus(User createdBy, PatientStatus status);
}
