package com.braintwinx.entity;

import com.braintwinx.exception.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An uploaded MRI scan and its analysis lifecycle.
 *
 * <p>Upload is the highest-severity untrusted-input boundary in the system, and this
 * entity records the outcome of that validation rather than the client's claims about
 * the file:
 *
 * <ul>
 *   <li>{@code storageKey} is generated server-side and is never derived from client
 *       input — the path-traversal defence required by brief section 9.</li>
 *   <li>{@code detectedMimeType} is what the server determined from the file's magic
 *       bytes, not the {@code Content-Type} the client declared.</li>
 *   <li>{@code originalFilename} is retained for display only and must never be used to
 *       build a filesystem path.</li>
 *   <li>{@code contentSha256} makes duplicate uploads detectable and lets a stored file
 *       be verified against the record.</li>
 * </ul>
 *
 * <p>Status changes go through {@link #transitionTo(ScanStatus)} so the state machine in
 * {@link ScanStatus} is the only route between states.
 */
@Entity
@Table(name = "scans")
public class Scan extends MutableEntity {

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, updatable = false)
    private Patient patient;

    @Column(name = "scan_date", nullable = false)
    private LocalDate scanDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_type", nullable = false, length = 16)
    private ScanType scanType;

    /** Server-generated. Never built from client-supplied text. */
    @Column(name = "storage_key", nullable = false, updatable = false, length = 512)
    private String storageKey;

    /** Display only. Must never reach a filesystem path. */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /** Detected by the server from file content, not declared by the client. */
    @Column(name = "detected_mime_type", nullable = false, length = 100)
    private String detectedMimeType;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "content_sha256", nullable = false, updatable = false, length = 64)
    private String contentSha256;

    @Column(name = "image_width")
    private Integer imageWidth;

    @Column(name = "image_height")
    private Integer imageHeight;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ScanStatus status = ScanStatus.UPLOADED;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_user_id", nullable = false, updatable = false)
    private User uploadedBy;

    protected Scan() {
        // Required by JPA.
    }

    public Scan(Patient patient,
                LocalDate scanDate,
                ScanType scanType,
                String storageKey,
                String detectedMimeType,
                long fileSizeBytes,
                String contentSha256,
                User uploadedBy) {
        this.publicId = UUID.randomUUID().toString();
        this.patient = patient;
        this.scanDate = scanDate;
        this.scanType = scanType;
        this.storageKey = storageKey;
        this.detectedMimeType = detectedMimeType;
        this.fileSizeBytes = fileSizeBytes;
        this.contentSha256 = contentSha256;
        this.uploadedBy = uploadedBy;
        this.status = ScanStatus.UPLOADED;
    }

    public String getPublicId() {
        return publicId;
    }

    public Patient getPatient() {
        return patient;
    }

    public LocalDate getScanDate() {
        return scanDate;
    }

    public ScanType getScanType() {
        return scanType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getDetectedMimeType() {
        return detectedMimeType;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public Integer getImageWidth() {
        return imageWidth;
    }

    public Integer getImageHeight() {
        return imageHeight;
    }

    public ScanStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public User getUploadedBy() {
        return uploadedBy;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    /** Recorded once the image has actually been decoded, not from client metadata. */
    public void recordDimensions(Integer width, Integer height) {
        this.imageWidth = width;
        this.imageHeight = height;
    }

    /**
     * Moves the scan to {@code target}, or rejects the move.
     *
     * <p>The only permitted route between statuses. Clearing {@code failureCode} on a
     * successful transition is required by the database CHECK constraint, which allows a
     * failure code only while the status is {@code FAILED} — so a retried scan cannot
     * carry a stale error.
     *
     * @throws InvalidStateTransitionException if the transition is not permitted
     */
    public void transitionTo(ScanStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException("Scan", status.name(),
                    target == null ? "null" : target.name());
        }
        this.status = target;
        if (target != ScanStatus.FAILED) {
            this.failureCode = null;
            this.failureReason = null;
        }
    }

    /**
     * Moves the scan to {@code FAILED} with a mandatory reason.
     *
     * <p>A failure code is required because the schema forbids a failed scan without
     * one: silent failure is not representable.
     *
     * @throws IllegalArgumentException if {@code failureCode} is absent
     */
    public void fail(String failureCode, String failureReason) {
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("A failure code is required when failing a scan");
        }
        if (!status.canTransitionTo(ScanStatus.FAILED)) {
            throw new InvalidStateTransitionException("Scan", status.name(), ScanStatus.FAILED.name());
        }
        this.status = ScanStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
    }

    /** @return {@code true} if analysis work is outstanding. */
    public boolean isInFlight() {
        return status.isInFlight();
    }

    /** Carries no patient-identifying data. */
    @Override
    public String toString() {
        return "Scan{publicId=" + publicId + ", status=" + status + ", type=" + scanType + "}";
    }
}
