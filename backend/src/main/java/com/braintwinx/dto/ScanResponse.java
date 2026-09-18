package com.braintwinx.dto;

import com.braintwinx.entity.ScanStatus;
import com.braintwinx.entity.ScanType;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Public representation of an MRI scan (project brief sections 8 and 18).
 *
 * <p>Deliberately omits the internal surrogate key, {@code storageKey}, and internal user IDs.
 * The scan is addressed externally by its {@code publicId}, and its parent patient by
 * {@code patientCode}.
 */
public record ScanResponse(
        String publicId,
        String patientCode,
        LocalDate scanDate,
        ScanType scanType,
        String originalFilename,
        String detectedMimeType,
        long fileSizeBytes,
        Integer imageWidth,
        Integer imageHeight,
        ScanStatus status,
        String failureCode,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {}
