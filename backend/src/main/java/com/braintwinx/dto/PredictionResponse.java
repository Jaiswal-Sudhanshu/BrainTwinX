package com.braintwinx.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Public representation of a scan tumor prediction.
 */
public record PredictionResponse(
        String publicId,
        String scanPublicId,
        String predictedClass,
        BigDecimal confidence,
        Map<String, BigDecimal> probabilities,
        String modelName,
        String modelVersion,
        String preprocessingVersion,
        Instant inferenceTimestamp,
        Integer inferenceDurationMs,
        boolean isSynthetic
) {}
