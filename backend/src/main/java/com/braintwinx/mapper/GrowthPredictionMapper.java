package com.braintwinx.mapper;

import com.braintwinx.dto.GrowthPredictionResponse;
import com.braintwinx.entity.GrowthPrediction;
import org.springframework.stereotype.Component;

@Component
public class GrowthPredictionMapper {

    public static final String MANDATORY_DISCLAIMER =
            "MODEL-BASED TREND ESTIMATE. Not a clinical diagnosis or guarantee of future growth.";

    public GrowthPredictionResponse toResponse(GrowthPrediction prediction) {
        if (prediction == null) {
            return null;
        }

        return new GrowthPredictionResponse(
                prediction.getPublicId(),
                prediction.getPatient().getPatientCode(),
                prediction.getTriggeringScan() != null ? prediction.getTriggeringScan().getPublicId() : null,
                prediction.getStatus(),
                prediction.getObservationCount(),
                prediction.getSpanDays(),
                prediction.getTrendDirection(),
                prediction.getForecastJson(),
                prediction.getModelVersion() != null ? prediction.getModelVersion().getModelName() : null,
                prediction.getModelVersion() != null ? prediction.getModelVersion().getModelVersion() : null,
                prediction.getPreprocessingVersion(),
                prediction.getInferenceTimestamp(),
                prediction.isSynthetic(),
                MANDATORY_DISCLAIMER,
                prediction.getCreatedAt()
        );
    }
}
