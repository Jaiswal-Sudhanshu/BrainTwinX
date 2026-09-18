package com.braintwinx.client;

import java.util.List;

/**
 * Request payload sent to AI microservice for longitudinal tumor growth forecasting.
 */
public record AiLongitudinalForecastRequest(
        String patientCode,
        List<ObservationPointDto> observations,
        List<Integer> forecastHorizonsDays
) {
    public record ObservationPointDto(
            String scanId,
            String scanDate,
            int daysFromFirst,
            long tumorAreaPx
    ) {}
}
