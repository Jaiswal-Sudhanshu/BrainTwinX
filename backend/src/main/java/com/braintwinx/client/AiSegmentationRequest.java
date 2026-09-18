package com.braintwinx.client;

/**
 * Request payload sent to AI microservice for U-Net tumor segmentation.
 */
public record AiSegmentationRequest(
        String scanId,
        String patientCode,
        String imageBase64
) {}
