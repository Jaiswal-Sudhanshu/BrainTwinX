package com.braintwinx.repository;

import com.braintwinx.entity.GrowthPrediction;
import com.braintwinx.entity.Patient;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link GrowthPrediction}.
 */
public interface GrowthPredictionRepository extends JpaRepository<GrowthPrediction, Long> {

    Optional<GrowthPrediction> findByPublicId(String publicId);

    /** Latest longitudinal outcome for a patient, whatever its status. */
    Optional<GrowthPrediction> findFirstByPatientOrderByCreatedAtDesc(Patient patient);

    Page<GrowthPrediction> findByPatientOrderByCreatedAtDesc(Patient patient, Pageable pageable);

    /** Safety assertion support: see {@link PredictionRepository#countBySyntheticTrue()}. */
    long countBySyntheticTrue();
}
