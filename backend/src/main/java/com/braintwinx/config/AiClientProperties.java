package com.braintwinx.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for communication with the internal Python AI inference service.
 */
@Validated
@ConfigurationProperties(prefix = "braintwinx.ai")
public class AiClientProperties {

    @NotBlank
    private String baseUrl = "http://localhost:8001";

    @Min(1000)
    private int timeoutMs = 30000;

    private String apiKey = "";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
