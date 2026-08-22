package com.braintwinx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A generated PDF report.
 *
 * <p>The links to prediction, segmentation, and growth results are all nullable on
 * purpose: a report remains issuable when a stage produced no result. The PDF then states
 * "Not available" for that section rather than omitting it, because an omitted section
 * could be misread as a negative clinical finding (brief section 34).
 *
 * <p>{@code explanationStatus} records <em>why</em> an explanation is absent — no provider
 * configured, or generated text rejected by the safety validator — so the distinction is
 * visible to a reader instead of appearing as an unexplained gap (ASSUMPTIONS.md A-3).
 *
 * <p>{@code contentSha256} lets a downloaded file be verified against the record, so a
 * report cannot be altered after issue without detection.
 */
@Entity
@Table(name = "reports")
public class Report extends BaseEntity {

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false, updatable = false)
    private Scan scan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, updatable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prediction_id")
    private Prediction prediction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "segmentation_result_id")
    private SegmentationResult segmentationResult;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "growth_prediction_id")
    private GrowthPrediction growthPrediction;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 512)
    private String storageKey;

    @Column(name = "content_sha256", nullable = false, updatable = false, length = 64)
    private String contentSha256;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "explanation_status", nullable = false, length = 16)
    private ExplanationStatus explanationStatus = ExplanationStatus.UNAVAILABLE;

    @Column(name = "explanation_text", columnDefinition = "text")
    private String explanationText;

    @Column(name = "explanation_provider", length = 64)
    private String explanationProvider;

    @Column(name = "explanation_model", length = 128)
    private String explanationModel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generated_by_user_id", nullable = false, updatable = false)
    private User generatedBy;

    protected Report() {
        // Required by JPA.
    }

    public Report(Scan scan,
                  Patient patient,
                  String storageKey,
                  String contentSha256,
                  long fileSizeBytes,
                  User generatedBy) {
        this.publicId = UUID.randomUUID().toString();
        this.scan = scan;
        this.patient = patient;
        this.storageKey = storageKey;
        this.contentSha256 = contentSha256;
        this.fileSizeBytes = fileSizeBytes;
        this.generatedBy = generatedBy;
        this.explanationStatus = ExplanationStatus.UNAVAILABLE;
    }

    public void linkResults(Prediction prediction,
                            SegmentationResult segmentationResult,
                            GrowthPrediction growthPrediction) {
        this.prediction = prediction;
        this.segmentationResult = segmentationResult;
        this.growthPrediction = growthPrediction;
    }

    /**
     * Attaches an explanation that passed safety validation.
     *
     * @throws IllegalArgumentException if the text is absent — the schema permits
     *         explanation text only when the status is {@code INCLUDED}
     */
    public void includeExplanation(String text, String provider, String model) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Explanation text is required when marking an explanation as included");
        }
        this.explanationStatus = ExplanationStatus.INCLUDED;
        this.explanationText = text;
        this.explanationProvider = provider;
        this.explanationModel = model;
    }

    /** Records that no explanation could be produced. Text is cleared, never retained. */
    public void markExplanationUnavailable() {
        this.explanationStatus = ExplanationStatus.UNAVAILABLE;
        this.explanationText = null;
    }

    /**
     * Records that generated text failed safety validation.
     *
     * <p>The rejected text is discarded rather than stored: retaining it would risk it
     * being surfaced later by a consumer that did not check the status.
     */
    public void markExplanationRejected(String provider, String model) {
        this.explanationStatus = ExplanationStatus.REJECTED;
        this.explanationText = null;
        this.explanationProvider = provider;
        this.explanationModel = model;
    }

    public String getPublicId() {
        return publicId;
    }

    public Scan getScan() {
        return scan;
    }

    public Patient getPatient() {
        return patient;
    }

    public Prediction getPrediction() {
        return prediction;
    }

    public SegmentationResult getSegmentationResult() {
        return segmentationResult;
    }

    public GrowthPrediction getGrowthPrediction() {
        return growthPrediction;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public ExplanationStatus getExplanationStatus() {
        return explanationStatus;
    }

    public String getExplanationText() {
        return explanationText;
    }

    public String getExplanationProvider() {
        return explanationProvider;
    }

    public String getExplanationModel() {
        return explanationModel;
    }

    public User getGeneratedBy() {
        return generatedBy;
    }

    @Override
    public String toString() {
        return "Report{publicId=" + publicId + ", explanation=" + explanationStatus + "}";
    }
}
