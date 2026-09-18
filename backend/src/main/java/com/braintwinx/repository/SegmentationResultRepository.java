package com.braintwinx.repository;

import com.braintwinx.entity.Scan;
import com.braintwinx.entity.SegmentationResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SegmentationResultRepository extends JpaRepository<SegmentationResult, Long> {

    Optional<SegmentationResult> findByPublicId(String publicId);

    Optional<SegmentationResult> findFirstByScanOrderByCreatedAtDesc(Scan scan);

    List<SegmentationResult> findByScanOrderByCreatedAtDesc(Scan scan);

    @Query("SELECT s FROM SegmentationResult s JOIN FETCH s.modelVersion WHERE s.scan = :scan ORDER BY s.createdAt DESC LIMIT 1")
    Optional<SegmentationResult> findLatestWithModelVersion(@Param("scan") Scan scan);
}
