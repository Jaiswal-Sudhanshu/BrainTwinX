package com.braintwinx.repository;

import com.braintwinx.entity.Scan;
import com.braintwinx.entity.SegmentationResult;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link SegmentationResult}.
 */
public interface SegmentationResultRepository extends JpaRepository<SegmentationResult, Long> {

    Optional<SegmentationResult> findByPublicId(String publicId);

    /** Latest segmentation for a scan, with its model version fetched for presentation. */
    @EntityGraph(attributePaths = "modelVersion")
    Optional<SegmentationResult> findFirstByScanOrderByCreatedAtDesc(Scan scan);

    /** Safety assertion support: see {@link PredictionRepository#countBySyntheticTrue()}. */
    long countBySyntheticTrue();
}
