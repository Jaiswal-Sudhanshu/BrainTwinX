package com.braintwinx.client;

import java.util.List;

/**
 * Response payload received from AI microservice longitudinal forecasting.
 *
 * NOTE: Results are strictly MODEL-BASED TREND ESTIMATES, never statements
 * of certainty about future growth (brief section 13 and section 44).
 */
public record AiLongitudinalForecastResponse(
        String modelName,
        String modelVersion,
        String preprocessingVersion,
        String trendDirection,
        List<ForecastPointDto> forecast,
        String disclaimer,
        boolean isSynthetic
) {
    public record ForecastPointDto(
            int horizonDays,
            long projectedAreaPx,
            Long projectedLowerPx,
            Long projectedUpperPx
    ) {}
}
