package com.braintwinx.repository;

import com.braintwinx.entity.Prediction;
import com.braintwinx.entity.Scan;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Prediction}.
 *
 * <p>Predictions are append-only, so "the result for a scan" means the most recent row
 * rather than the only row. Earlier predictions are retained so an already-issued report
 * stays explainable by the record behind it.
 */
public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    Optional<Prediction> findByPublicId(String publicId);

    /**
     * Latest prediction for a scan, with its model version fetched.
     *
     * <p>The model version is always needed alongside the result — every presentation of a
     * prediction must state which model produced it (brief section 22) — so fetching it
     * eagerly here avoids a second query per lookup.
     */
    @EntityGraph(attributePaths = "modelVersion")
    Optional<Prediction> findFirstByScanOrderByCreatedAtDesc(Scan scan);

    /** Full prediction history for a scan, newest first. */
    List<Prediction> findByScanOrderByCreatedAtDesc(Scan scan);

    /**
     * Counts synthetic rows.
     *
     * <p>Exists to support the safety assertion that no development-stub output is present
     * outside development (ASSUMPTIONS.md A-15). A non-zero count in a production database
     * is a defect, and this makes it detectable rather than invisible.
     */
    long countBySyntheticTrue();
}
