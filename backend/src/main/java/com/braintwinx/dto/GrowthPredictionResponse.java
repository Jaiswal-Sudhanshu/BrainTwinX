package com.braintwinx.dto;

import com.braintwinx.entity.GrowthAnalysisStatus;
import com.braintwinx.entity.TrendDirection;
import java.time.Instant;

/**
 * Public DTO representing a longitudinal growth analysis outcome (brief section 13).
 *
 * <p><strong>Medical safety invariants:</strong>
 * <ul>
 *   <li>Results are strictly <strong>model-based trend estimates</strong>, never statements
 *       of certainty about future growth (brief section 44).</li>
 *   <li>When {@code status} is {@link GrowthAnalysisStatus#INSUFFICIENT_HISTORY}, {@code forecastJson},
 *       {@code trendDirection}, and model metadata are {@code null}. No trend or extrapolation
 *       is fabricated.</li>
 * </ul>
 */
public record GrowthPredictionResponse(
        String publicId,
        String patientCode,
        String triggeringScanPublicId,
        GrowthAnalysisStatus status,
        int observationCount,
        Integer spanDays,
        TrendDirection trendDirection,
        String forecastJson,
        String modelName,
        String modelVersion,
        String preprocessingVersion,
        Instant inferenceTimestamp,
        boolean isSynthetic,
        String disclaimer,
        Instant createdAt
) {}
