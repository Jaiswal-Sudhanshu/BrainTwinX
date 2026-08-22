package com.braintwinx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A registered model version (brief section 53).
 *
 * <p>Every inference references the exact row that produced it, which is what makes a
 * result reproducible (brief section 22). Superseded versions are retained rather than
 * deleted so an already-issued report can still be attributed to the model behind it.
 *
 * <p>{@code preprocessingVersion} is part of the model identity on purpose: a model is
 * only valid for the preprocessing contract it was trained against, and changing
 * preprocessing without registering a new model version is a correctness bug
 * (brief section 10).
 */
@Entity
@Table(name = "model_versions")
public class ModelVersion extends MutableEntity {

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_type", nullable = false, length = 16)
    private ModelType modelType;

    @Column(name = "version", nullable = false, length = 32)
    private String modelVersion;

    @Column(name = "framework", nullable = false, length = 64)
    private String framework;

    /**
     * SHA-256 of the weights file. When present, a mismatch at load time must cause a
     * refusal to load rather than a silent substitution of unknown weights.
     */
    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    /** Expected input tensor shape, e.g. {@code 1x3x224x224}. */
    @Column(name = "input_shape", length = 64)
    private String inputShape;

    @Column(name = "preprocessing_version", nullable = false, length = 32)
    private String preprocessingVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ModelStatus status = ModelStatus.INACTIVE;

    @Column(name = "notes", length = 512)
    private String notes;

    protected ModelVersion() {
        // Required by JPA.
    }

    public ModelVersion(String modelName,
                        ModelType modelType,
                        String modelVersion,
                        String framework,
                        String preprocessingVersion) {
        this.modelName = modelName;
        this.modelType = modelType;
        this.modelVersion = modelVersion;
        this.framework = framework;
        this.preprocessingVersion = preprocessingVersion;
        this.status = ModelStatus.INACTIVE;
    }

    public String getModelName() {
        return modelName;
    }

    public ModelType getModelType() {
        return modelType;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getFramework() {
        return framework;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public String getInputShape() {
        return inputShape;
    }

    public String getPreprocessingVersion() {
        return preprocessingVersion;
    }

    public ModelStatus getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public void setChecksumSha256(String checksumSha256) {
        this.checksumSha256 = checksumSha256;
    }

    public void setInputShape(String inputShape) {
        this.inputShape = inputShape;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public void activate() {
        this.status = ModelStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = ModelStatus.INACTIVE;
    }

    public void deprecate() {
        this.status = ModelStatus.DEPRECATED;
    }

    /** @return {@code true} if this version may be selected to serve new inference. */
    public boolean isServable() {
        return status == ModelStatus.ACTIVE;
    }

    /** Human-readable identity, e.g. {@code BrainTumorClassifier v1.0.0}. */
    public String describe() {
        return modelName + " v" + modelVersion;
    }

    @Override
    public String toString() {
        return "ModelVersion{" + describe() + ", type=" + modelType + ", status=" + status + "}";
    }
}
