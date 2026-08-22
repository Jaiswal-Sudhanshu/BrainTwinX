package com.braintwinx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A longitudinal growth analysis outcome (brief section 13).
 *
 * <p>Results here are <strong>model-based trend estimates</strong>, never statements of
 * certainty about future growth (brief section 44).
 *
 * <p><strong>The central invariant.</strong> {@code INSUFFICIENT_HISTORY} is a
 * first-class recorded outcome, not an error and not an excuse to extrapolate. The two
 * factory methods are the only ways to construct this entity, and the insufficient-history
 * factory cannot accept a forecast, a trend direction, or a model reference. The database
 * enforces the same rule with a CHECK constraint, so a fabricated trend cannot be stored
 * next to an admission of insufficient data even by a future code path that tried.
 *
 * <p>{@code observationCount} and {@code spanDays} are always recorded, so the sufficiency
 * decision itself is auditable rather than opaque.
 */
@Entity
@Table(name = "growth_predictions")
public class GrowthPrediction extends BaseEntity {

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, updatable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "triggering_scan_id")
    private Scan triggeringScan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_job_id")
    private AnalysisJob analysisJob;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private GrowthAnalysisStatus status;

    /** Evidence for the sufficiency decision. Always recorded. */
    @Column(name = "observation_count", nullable = false)
    private int observationCount;

    @Column(name = "span_days")
    private Integer spanDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "trend_direction", length = 16)
    private TrendDirection trendDirection;

    /** Forecast series as returned by the model. Null unless a model actually ran. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "forecast")
    private String forecastJson;

    /** Null when no model executed, so there is nothing to attribute the outcome to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_version_id")
    private ModelVersion modelVersion;

    @Column(name = "preprocessing_version", length = 32)
    private String preprocessingVersion;

    @Column(name = "inference_timestamp")
    private Instant inferenceTimestamp;

    @Column(name = "is_synthetic", nullable = false)
    private boolean synthetic;

    protected GrowthPrediction() {
        // Required by JPA.
    }

    /**
     * Records that history was insufficient to support any trend statement.
     *
     * <p>Takes no forecast, trend, or model parameter by design: there is no way to
     * express a prediction through this path.
     *
     * @param observationCount number of valid observations found
     * @param spanDays         time span covered, or {@code null} if fewer than two points
     */
    public static GrowthPrediction insufficientHistory(Patient patient,
                                                       Scan triggeringScan,
                                                       AnalysisJob analysisJob,
                                                       int observationCount,
                                                       Integer spanDays) {
        GrowthPrediction result = new GrowthPrediction();
        result.publicId = UUID.randomUUID().toString();
        result.patient = patient;
        result.triggeringScan = triggeringScan;
        result.analysisJob = analysisJob;
        result.status = GrowthAnalysisStatus.INSUFFICIENT_HISTORY;
        result.observationCount = Math.max(observationCount, 0);
        result.spanDays = spanDays;
        result.synthetic = false;
        // forecast, trendDirection, modelVersion, inferenceTimestamp remain null.
        return result;
    }

    /**
     * Records a completed model-based trend estimate.
     *
     * @throws IllegalArgumentException if the model attribution or trend is missing —
     *         a completed estimate must be attributable to a model (schema constraint
     *         {@code ck_growth_completed_has_model})
     */
    public static GrowthPrediction completed(Patient patient,
                                             Scan triggeringScan,
                                             AnalysisJob analysisJob,
                                             int observationCount,
                                             Integer spanDays,
                                             TrendDirection trendDirection,
                                             String forecastJson,
                                             ModelVersion modelVersion,
                                             String preprocessingVersion,
                                             Instant inferenceTimestamp,
                                             boolean synthetic) {
        if (trendDirection == null) {
            throw new IllegalArgumentException("A completed trend estimate requires a direction");
        }
        if (modelVersion == null) {
            throw new IllegalArgumentException(
                    "A completed trend estimate must be attributable to a model version");
        }
        if (inferenceTimestamp == null) {
            throw new IllegalArgumentException("A completed trend estimate requires an inference timestamp");
        }
        GrowthPrediction result = new GrowthPrediction();
        result.publicId = UUID.randomUUID().toString();
        result.patient = patient;
        result.triggeringScan = triggeringScan;
        result.analysisJob = analysisJob;
        result.status = GrowthAnalysisStatus.COMPLETED;
        result.observationCount = observationCount;
        result.spanDays = spanDays;
        result.trendDirection = trendDirection;
        result.forecastJson = forecastJson;
        result.modelVersion = modelVersion;
        result.preprocessingVersion = preprocessingVersion;
        result.inferenceTimestamp = inferenceTimestamp;
        result.synthetic = synthetic;
        return result;
    }

    /** Records that history was sufficient but the analysis itself failed. */
    public static GrowthPrediction failed(Patient patient,
                                          Scan triggeringScan,
                                          AnalysisJob analysisJob,
                                          int observationCount,
                                          Integer spanDays) {
        GrowthPrediction result = new GrowthPrediction();
        result.publicId = UUID.randomUUID().toString();
        result.patient = patient;
        result.triggeringScan = triggeringScan;
        result.analysisJob = analysisJob;
        result.status = GrowthAnalysisStatus.FAILED;
        result.observationCount = observationCount;
        result.spanDays = spanDays;
        result.synthetic = false;
        return result;
    }

    public String getPublicId() {
        return publicId;
    }

    public Patient getPatient() {
        return patient;
    }

    public Scan getTriggeringScan() {
        return triggeringScan;
    }

    public AnalysisJob getAnalysisJob() {
        return analysisJob;
    }

    public GrowthAnalysisStatus getStatus() {
        return status;
    }

    public int getObservationCount() {
        return observationCount;
    }

    public Integer getSpanDays() {
        return spanDays;
    }

    public TrendDirection getTrendDirection() {
        return trendDirection;
    }

    public String getForecastJson() {
        return forecastJson;
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

    public boolean isSynthetic() {
        return synthetic;
    }

    /** @return {@code true} if a trend estimate is actually available to present */
    public boolean hasTrendEstimate() {
        return status == GrowthAnalysisStatus.COMPLETED && trendDirection != null;
    }

    @Override
    public String toString() {
        return "GrowthPrediction{publicId=" + publicId + ", status=" + status
                + ", observations=" + observationCount + "}";
    }
}
