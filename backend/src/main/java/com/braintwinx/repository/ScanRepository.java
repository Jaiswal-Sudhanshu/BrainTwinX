package com.braintwinx.repository;

import com.braintwinx.entity.Patient;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.ScanStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Scan}.
 */
public interface ScanRepository extends JpaRepository<Scan, Long> {

    Optional<Scan> findByPublicId(String publicId);

    /**
     * Fetches a scan together with its patient in one query.
     *
     * <p>Authorisation checks need the patient, and the lazy association would otherwise
     * trigger a second select on every scan lookup — the N+1 pattern brief section 16
     * warns against.
     */
    @EntityGraph(attributePaths = "patient")
    Optional<Scan> findWithPatientByPublicId(String publicId);

    /** Paginated scan list for one patient, newest scan date first. */
    Page<Scan> findByPatientOrderByScanDateDesc(Patient patient, Pageable pageable);

    /**
     * Full chronological history for one patient, oldest first.
     *
     * <p>Ordered ascending because longitudinal analysis needs the series in time order,
     * and sorting it in memory afterwards would be redundant work.
     */
    List<Scan> findByPatientAndStatusOrderByScanDateAsc(Patient patient, ScanStatus status);

    List<Scan> findByPatientOrderByScanDateAsc(Patient patient);

    /** Duplicate-upload detection, matching the unique constraint on the same pair. */
    boolean existsByPatientAndContentSha256(Patient patient, String contentSha256);

    /** Dashboard counts of outstanding and completed work. */
    long countByStatus(ScanStatus status);
}
