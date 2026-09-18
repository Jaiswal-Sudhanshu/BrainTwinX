package com.braintwinx.client;

/**
 * Response payload received from AI microservice U-Net segmentation.
 *
 * NOTE: There are deliberately NO Dice or IoU fields here.
 * Those metrics require ground-truth masks and belong strictly
 * to the offline evaluation harness (brief section 12).
 */
public record AiSegmentationResponse(
        String modelName,
        String modelVersion,
        String preprocessingVersion,
        boolean tumorDetected,
        Long tumorAreaPx,
        Integer maskWidth,
        Integer maskHeight,
        Integer bboxX,
        Integer bboxY,
        Integer bboxWidth,
        Integer bboxHeight,
        String maskBase64,
        boolean isSynthetic
) {}
