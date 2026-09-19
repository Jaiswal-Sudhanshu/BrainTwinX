package com.braintwinx.explanation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LLM-backed explanation provider with fail-closed safety semantics.
 *
 * <p>If the API key is not configured or an external error occurs, this provider
 * returns {@link ExplanationResult#unavailable(String, String)} rather than crashing
 * or surfacing partial hallucinations.
 */
@Component
public class LlmExplanationProvider implements ExplanationProvider {

    private static final Logger log = LoggerFactory.getLogger(LlmExplanationProvider.class);
    public static final String PROVIDER_NAME = "LLM_EXTERNAL_PROVIDER";

    private final String apiKey;
    private final String model;
    private final String endpoint;

    public LlmExplanationProvider(
            @Value("${braintwinx.explanation.llm.api-key:}") String apiKey,
            @Value("${braintwinx.explanation.llm.model:clinical-llm-v1}") String model,
            @Value("${braintwinx.explanation.llm.endpoint:}") String endpoint
    ) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = model != null ? model.trim() : "clinical-llm-v1";
        this.endpoint = endpoint != null ? endpoint.trim() : "";
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public ExplanationResult generateExplanation(ExplanationPayload payload) {
        if (apiKey.isEmpty() || endpoint.isEmpty()) {
            log.info("LLM explanation provider requested but unconfigured (apiKey or endpoint absent). Failing closed.");
            return ExplanationResult.unavailable("LLM_PROVIDER_NOT_CONFIGURED", PROVIDER_NAME);
        }

        // When external integration is enabled, prompt would be constructed strictly from payload:
        // System prompt contains the 10 Clinical Safety Prohibitions and instructions to format
        // decision-support findings with mandatory radiologist review disclaimer.
        try {
            // Live HTTP call would happen here with RestClient/WebClient.
            // In absence of live endpoint test harness, return unavailable or mock response.
            return ExplanationResult.unavailable("LLM_ENDPOINT_CONNECTIVITY_DEFERRED", PROVIDER_NAME);
        } catch (Exception e) {
            log.error("Failed to generate explanation from LLM provider: {}", e.getMessage(), e);
            return ExplanationResult.unavailable("LLM_PROVIDER_ERROR: " + e.getMessage(), PROVIDER_NAME);
        }
    }
}
