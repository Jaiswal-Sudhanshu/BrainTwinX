package com.braintwinx.repository;

import com.braintwinx.entity.ModelType;
import com.braintwinx.entity.ModelStatus;
import com.braintwinx.entity.ModelVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ModelVersion} — the model registry (brief section 53).
 */
public interface ModelVersionRepository extends JpaRepository<ModelVersion, Long> {

    Optional<ModelVersion> findByModelNameAndModelVersion(String modelName, String modelVersion);

    /**
     * The version currently serving a given model type.
     *
     * <p>Returns a list rather than an {@code Optional} deliberately: if more than one row
     * is ACTIVE for a type, that is a registry defect the caller must detect and refuse to
     * guess about, rather than have silently resolved by picking the first match.
     */
    List<ModelVersion> findByModelTypeAndStatus(ModelType modelType, ModelStatus status);

    List<ModelVersion> findByModelTypeOrderByCreatedAtDesc(ModelType modelType);

    boolean existsByModelNameAndModelVersion(String modelName, String modelVersion);
}
