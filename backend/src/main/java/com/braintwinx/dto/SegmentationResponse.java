package com.braintwinx.dto;

import java.time.Instant;

/**
 * Public DTO representing a U-Net tumor segmentation result.
 *
 * <p><strong>Medical safety invariant:</strong>
 * Dice score and IoU are deliberately absent because they require ground truth,
 * which does not exist for clinical scans. Area units are explicitly in pixels
 * of the preprocessed image ({@code tumorAreaPx}), not mm² (ASSUMPTIONS.md A-6, A-7).
 */
public record SegmentationResponse(
        String publicId,
        String scanPublicId,
        boolean tumorDetected,
        Long tumorAreaPx,
        Integer maskWidth,
        Integer maskHeight,
        Integer bboxX,
        Integer bboxY,
        Integer bboxWidth,
        Integer bboxHeight,
        boolean hasMaskFile,
        String modelName,
        String modelVersion,
        String preprocessingVersion,
        boolean isSynthetic,
        Instant inferenceTimestamp,
        Instant createdAt
) {}
