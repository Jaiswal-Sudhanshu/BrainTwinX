package com.braintwinx.mapper;

import com.braintwinx.dto.PredictionResponse;
import com.braintwinx.entity.Prediction;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hand-written one-way mapper for {@link Prediction}.
 *
 * <p>Never exposes database primary keys or internal foreign keys.
 */
@Component
public class PredictionMapper {

    private static final Logger log = LoggerFactory.getLogger(PredictionMapper.class);
    private final ObjectMapper objectMapper;

    public PredictionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PredictionResponse toResponse(Prediction prediction) {
        Map<String, BigDecimal> probabilities = parseProbabilities(prediction.getProbabilitiesJson());
        return new PredictionResponse(
                prediction.getPublicId(),
                prediction.getScan().getPublicId(),
                prediction.getPredictedClass(),
                prediction.getConfidence(),
                probabilities,
                prediction.getModelVersion() != null ? prediction.getModelVersion().getModelName() : null,
                prediction.getModelVersion() != null ? prediction.getModelVersion().getModelVersion() : null,
                prediction.getPreprocessingVersion(),
                prediction.getInferenceTimestamp(),
                prediction.getInferenceDurationMs(),
                prediction.isSynthetic()
        );
    }

    private Map<String, BigDecimal> parseProbabilities(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, BigDecimal>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse prediction probabilities JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
