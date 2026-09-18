package com.braintwinx.client;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Response payload received from the AI service after tumor classification.
 */
public record AiClassificationResponse(
        String modelName,
        String modelVersion,
        String preprocessingVersion,
        String tumorType,
        BigDecimal confidence,
        Map<String, BigDecimal> probabilities,
        boolean isSynthetic
) {}
