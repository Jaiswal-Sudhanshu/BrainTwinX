package com.braintwinx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A CNN classification result.
 *
 * <p>Append-only: re-analysis inserts a new row rather than overwriting, so an
 * already-issued report stays explainable by the exact record it was built from.
 *
 * <p><strong>Medical-safety notes.</strong>
 *
 * <ul>
 *   <li>{@code confidence} is non-null and constrained to [0,1] by the schema. A
 *       prediction without a genuine model confidence is not representable, and no code
 *       path may supply a literal or randomly generated value (ASSUMPTIONS.md A-15).</li>
 *   <li>{@code probabilities} keeps the full distribution, not just the winning class, so
 *       a result can be re-examined rather than only its argmax.</li>
 *   <li>{@code synthetic} must be stated explicitly at construction — there is no default.
 *       A caller cannot forget to declare whether output came from a real model, which is
 *       what keeps development-stub output from ever being mistaken for clinical
 *       output (ASSUMPTIONS.md A-2).</li>
 *   <li>{@code modelVersion} and {@code preprocessingVersion} are mandatory, making every
 *       result reproducible (brief section 22).</li>
 * </ul>
 */
@Entity
@Table(name = "predictions")
public class Prediction extends BaseEntity {

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false, updatable = false)
    private Scan scan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_job_id")
    private AnalysisJob analysisJob;

    @Column(name = "predicted_class", nullable = false, length = 64)
    private String predictedClass;

    /** Real model output. Precision 6 / scale 5 gives the range [0.00000, 1.00000]. */
    @Column(name = "confidence", nullable = false, precision = 6, scale = 5)
    private BigDecimal confidence;

    /** Full probability distribution over all classes, as returned by the model. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "probabilities", nullable = false)
    private String probabilitiesJson;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_version_id", nullable = false, updatable = false)
    private ModelVersion modelVersion;

    @Column(name = "preprocessing_version", nullable = false, length = 32)
    private String preprocessingVersion;

    @Column(name = "inference_timestamp", nullable = false)
    private Instant inferenceTimestamp;

    @Column(name = "inference_duration_ms")
    private Integer inferenceDurationMs;

    /**
     * {@code true} only for development-stub output. Lets any consumer — API, report, or
     * UI — refuse to present the row as clinical.
     */
    @Column(name = "is_synthetic", nullable = false)
    private boolean synthetic;

    protected Prediction() {
        // Required by JPA.
    }

    /**
     * @param confidence           model-derived confidence in [0,1]; must not be fabricated
     * @param probabilitiesJson    full distribution as a JSON object
     * @param synthetic            {@code true} only for development-stub output
     * @throws IllegalArgumentException if confidence is absent or outside [0,1]
     */
    public Prediction(Scan scan,
                      AnalysisJob analysisJob,
                      String predictedClass,
                      BigDecimal confidence,
                      String probabilitiesJson,
                      ModelVersion modelVersion,
                      String preprocessingVersion,
                      Instant inferenceTimestamp,
                      boolean synthetic) {
        if (confidence == null) {
            throw new IllegalArgumentException(
                    "Confidence is required: a prediction without real model confidence "
                            + "must not be persisted");
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Confidence must be within [0,1] but was " + confidence);
        }
        if (probabilitiesJson == null || probabilitiesJson.isBlank()) {
            throw new IllegalArgumentException("The full probability distribution is required");
        }
        this.publicId = UUID.randomUUID().toString();
        this.scan = scan;
        this.analysisJob = analysisJob;
        this.predictedClass = predictedClass;
        this.confidence = confidence;
        this.probabilitiesJson = probabilitiesJson;
        this.modelVersion = modelVersion;
        this.preprocessingVersion = preprocessingVersion;
        this.inferenceTimestamp = inferenceTimestamp;
        this.synthetic = synthetic;
    }

    public String getPublicId() {
        return publicId;
    }

    public Scan getScan() {
        return scan;
    }

    public AnalysisJob getAnalysisJob() {
        return analysisJob;
    }

    public String getPredictedClass() {
        return predictedClass;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getProbabilitiesJson() {
        return probabilitiesJson;
    }

    public ModelVersion getModelVersion() {
        return modelVersion;
    }

    public String getPreprocessingVersion() {
        return preprocessingVersion;
    }

    public Instant getInferenceTimestamp() {
        return inferenceTimestamp;
    }

    public Integer getInferenceDurationMs() {
        return inferenceDurationMs;
    }

    /**
     * @return {@code true} if this row is development-stub output and must never be
     *         presented as a clinical result
     */
    public boolean isSynthetic() {
        return synthetic;
    }

    public void setInferenceDurationMs(Integer inferenceDurationMs) {
        this.inferenceDurationMs = inferenceDurationMs;
    }

    @Override
    public String toString() {
        return "Prediction{publicId=" + publicId + ", class=" + predictedClass
                + ", synthetic=" + synthetic + "}";
    }
}
