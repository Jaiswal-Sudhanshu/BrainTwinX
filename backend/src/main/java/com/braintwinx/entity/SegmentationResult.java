package com.braintwinx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A U-Net segmentation result.
 *
 * <p><strong>Deliberately absent: Dice and IoU.</strong> Those metrics require a
 * ground-truth mask, which does not exist for a production scan. There is therefore no
 * field to hold one, so no code path can attach a fabricated accuracy figure to a single
 * inference (brief section 12). Dice and IoU belong exclusively to the offline evaluation
 * harness, where real ground truth is available.
 *
 * <p>{@code tumorAreaPx} carries its unit in the name: pixels of the <em>preprocessed</em>
 * image. Physical area in mm² would need pixel-spacing metadata that 2-D PNG/JPEG inputs
 * do not carry, and deriving mm² from an assumed spacing would be a fabricated
 * measurement (ASSUMPTIONS.md A-6, A-7).
 *
 * <p>The mask is stored as a file artefact referenced by {@code maskStorageKey}, not as a
 * database blob (brief section 4). The original scan is never modified.
 */
@Entity
@Table(name = "segmentation_results")
public class SegmentationResult extends BaseEntity {

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false, updatable = false)
    private Scan scan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prediction_id")
    private Prediction prediction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_job_id")
    private AnalysisJob analysisJob;

    @Column(name = "tumor_detected", nullable = false)
    private boolean tumorDetected;

    /** Reference to the mask artefact. Null when nothing was detected. */
    @Column(name = "mask_storage_key", length = 512)
    private String maskStorageKey;

    /** Area in pixels of the preprocessed image. Not mm². Null when nothing was detected. */
    @Column(name = "tumor_area_px")
    private Long tumorAreaPx;

    @Column(name = "mask_width")
    private Integer maskWidth;

    @Column(name = "mask_height")
    private Integer maskHeight;

    @Column(name = "bbox_x")
    private Integer bboxX;

    @Column(name = "bbox_y")
    private Integer bboxY;

    @Column(name = "bbox_width")
    private Integer bboxWidth;

    @Column(name = "bbox_height")
    private Integer bboxHeight;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_version_id", nullable = false, updatable = false)
    private ModelVersion modelVersion;

    @Column(name = "preprocessing_version", nullable = false, length = 32)
    private String preprocessingVersion;

    @Column(name = "inference_timestamp", nullable = false)
    private Instant inferenceTimestamp;

    @Column(name = "inference_duration_ms")
    private Integer inferenceDurationMs;

    /** {@code true} only for development-stub output. Stated explicitly at construction. */
    @Column(name = "is_synthetic", nullable = false)
    private boolean synthetic;

    protected SegmentationResult() {
        // Required by JPA.
    }

    /**
     * Creates a result in which no tumour region was detected.
     *
     * <p>A separate factory from the detected case because the schema forbids an area or
     * bounding box alongside {@code tumorDetected = false}: a "not detected, but here is
     * its size" contradiction is not representable.
     */
    public static SegmentationResult notDetected(Scan scan,
                                                 AnalysisJob analysisJob,
                                                 ModelVersion modelVersion,
                                                 String preprocessingVersion,
                                                 Instant inferenceTimestamp,
                                                 boolean synthetic) {
        SegmentationResult result = new SegmentationResult();
        result.publicId = UUID.randomUUID().toString();
        result.scan = scan;
        result.analysisJob = analysisJob;
        result.tumorDetected = false;
        result.modelVersion = modelVersion;
        result.preprocessingVersion = preprocessingVersion;
        result.inferenceTimestamp = inferenceTimestamp;
        result.synthetic = synthetic;
        return result;
    }

    /**
     * Creates a result in which a tumour region was detected.
     *
     * @param tumorAreaPx      area in pixels of the preprocessed image, from the mask
     * @param maskStorageKey   reference to the stored mask artefact
     * @throws IllegalArgumentException if the area or mask reference is missing
     */
    public static SegmentationResult detected(Scan scan,
                                              AnalysisJob analysisJob,
                                              String maskStorageKey,
                                              long tumorAreaPx,
                                              int maskWidth,
                                              int maskHeight,
                                              ModelVersion modelVersion,
                                              String preprocessingVersion,
                                              Instant inferenceTimestamp,
                                              boolean synthetic) {
        if (maskStorageKey == null || maskStorageKey.isBlank()) {
            throw new IllegalArgumentException("A detected region requires a stored mask");
        }
        if (tumorAreaPx < 0) {
            throw new IllegalArgumentException("Tumour area cannot be negative");
        }
        SegmentationResult result = new SegmentationResult();
        result.publicId = UUID.randomUUID().toString();
        result.scan = scan;
        result.analysisJob = analysisJob;
        result.tumorDetected = true;
        result.maskStorageKey = maskStorageKey;
        result.tumorAreaPx = tumorAreaPx;
        result.maskWidth = maskWidth;
        result.maskHeight = maskHeight;
        result.modelVersion = modelVersion;
        result.preprocessingVersion = preprocessingVersion;
        result.inferenceTimestamp = inferenceTimestamp;
        result.synthetic = synthetic;
        return result;
    }

    /**
     * Attaches a bounding box. All four values are required together, matching the schema
     * constraint that treats a box as all-or-nothing.
     *
     * @throws IllegalStateException    if no region was detected
     * @throws IllegalArgumentException if the box has non-positive extent
     */
    public void withBoundingBox(int x, int y, int width, int height) {
        if (!tumorDetected) {
            throw new IllegalStateException("A bounding box requires a detected region");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Bounding box extent must be positive");
        }
        this.bboxX = x;
        this.bboxY = y;
        this.bboxWidth = width;
        this.bboxHeight = height;
    }

    public void linkPrediction(Prediction prediction) {
        this.prediction = prediction;
    }

    public void setInferenceDurationMs(Integer inferenceDurationMs) {
        this.inferenceDurationMs = inferenceDurationMs;
    }

    public String getPublicId() {
        return publicId;
    }

    public Scan getScan() {
        return scan;
    }

    public Prediction getPrediction() {
        return prediction;
    }

    public AnalysisJob getAnalysisJob() {
        return analysisJob;
    }

    public boolean isTumorDetected() {
        return tumorDetected;
    }

    public String getMaskStorageKey() {
        return maskStorageKey;
    }

    /** @return area in pixels of the preprocessed image, or {@code null} if not detected */
    public Long getTumorAreaPx() {
        return tumorAreaPx;
    }

    public Integer getMaskWidth() {
        return maskWidth;
    }

    public Integer getMaskHeight() {
        return maskHeight;
    }

    public Integer getBboxX() {
        return bboxX;
    }

    public Integer getBboxY() {
        return bboxY;
    }

    public Integer getBboxWidth() {
        return bboxWidth;
    }

    public Integer getBboxHeight() {
        return bboxHeight;
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

    public boolean isSynthetic() {
        return synthetic;
    }

    @Override
    public String toString() {
        return "SegmentationResult{publicId=" + publicId + ", detected=" + tumorDetected
                + ", synthetic=" + synthetic + "}";
    }
}
