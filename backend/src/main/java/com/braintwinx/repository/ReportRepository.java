package com.braintwinx.repository;

import com.braintwinx.entity.Patient;
import com.braintwinx.entity.Report;
import com.braintwinx.entity.Scan;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Report}.
 */
public interface ReportRepository extends JpaRepository<Report, Long> {

    Optional<Report> findByPublicId(String publicId);

    /**
     * Fetches a report with the patient and scan needed to authorise access to it.
     *
     * <p>Download is an IDOR-sensitive operation: knowing a report identifier must not be
     * sufficient to retrieve it. The authorisation check needs these associations, so they
     * are fetched in the same query.
     */
    @EntityGraph(attributePaths = {"patient", "scan"})
    Optional<Report> findWithContextByPublicId(String publicId);

    Page<Report> findByPatientOrderByCreatedAtDesc(Patient patient, Pageable pageable);

    List<Report> findByScanOrderByCreatedAtDesc(Scan scan);
}
