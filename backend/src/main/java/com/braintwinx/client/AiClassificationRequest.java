package com.braintwinx.client;

/**
 * Request payload sent to the AI service for tumor classification.
 */
public record AiClassificationRequest(
        String scanId,
        String patientCode,
        String imageBase64
) {}
