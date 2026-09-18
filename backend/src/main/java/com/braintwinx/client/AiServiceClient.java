package com.braintwinx.client;

import com.braintwinx.config.AiClientProperties;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Client for communicating with the internal Python AI service over HTTP.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Strict read and connect timeouts.</li>
 *   <li>Authentication via internal API key header.</li>
 *   <li>Typed mapping to {@link ApiErrorCode#AI_SERVICE_UNAVAILABLE} on failure without hanging.</li>
 * </ul>
 */
@Service
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-API-Key";

    private final RestClient restClient;
    private final AiClientProperties properties;

    @Autowired
    public AiServiceClient(AiClientProperties properties) {
        this(properties, RestClient.builder(), true);
    }

    AiServiceClient(AiClientProperties properties, RestClient.Builder restClientBuilder, boolean applyRequestFactory) {
        this.properties = properties;

        RestClient.Builder builder = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        if (applyRequestFactory) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofMillis(properties.getTimeoutMs()));
            requestFactory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMs()));
            builder.requestFactory(requestFactory);
        }

        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.defaultHeader(INTERNAL_API_KEY_HEADER, properties.getApiKey());
        }

        this.restClient = builder.build();
    }

    /**
     * Checks liveness of the internal AI service.
     */
    public AiHealthResponse checkLiveness() {
        try {
            return restClient.get()
                    .uri("/internal/ai/v1/health")
                    .retrieve()
                    .body(AiHealthResponse.class);
        } catch (Exception e) {
            log.warn("AI service liveness check failed at baseUrl {}: {}", properties.getBaseUrl(), e.getMessage());
            throw new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE, "AI inference service is unreachable", e);
        }
    }

    /**
     * Checks readiness of the internal AI service.
     * When unready (e.g. 503 or weights missing), captures the status.
     */
    public AiReadyResponse checkReadiness() {
        try {
            return restClient.get()
                    .uri("/internal/ai/v1/ready")
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        // Handled by returning body or letting caller inspect
                    })
                    .body(AiReadyResponse.class);
        } catch (RestClientResponseException e) {
            try {
                AiReadyResponse unready = e.getResponseBodyAs(AiReadyResponse.class);
                if (unready != null) {
                    return unready;
                }
            } catch (Exception ignored) {
                // fall through to general failure
            }
            log.warn("AI service readiness check failed with status {}: {}", e.getStatusCode(), e.getMessage());
            throw new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE, "AI inference service reported unavailable", e);
        } catch (Exception e) {
            log.warn("AI service readiness check failed at baseUrl {}: {}", properties.getBaseUrl(), e.getMessage());
            throw new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE, "AI inference service is unreachable", e);
        }
    }

    /**
     * Asserts that the AI service is ready to serve inference requests.
     * Throws {@link ApiErrorCode#AI_SERVICE_UNAVAILABLE} if not ready.
     */
    public void ensureReady() {
        AiReadyResponse readiness = checkReadiness();
        if (readiness == null || !readiness.ready()) {
            String reason = readiness != null ? readiness.reason() : "Unknown";
            log.warn("AI service is not ready to serve inference requests: {}", reason);
            throw new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE,
                    "AI service is not ready to serve inference: " + reason);
        }
    }

    /**
     * Invokes the internal AI service to classify a brain MRI slice.
     *
     * @param request classification request payload
     * @return {@link AiClassificationResponse} classification output
     */
    public AiClassificationResponse classify(AiClassificationRequest request) {
        try {
            return restClient.post()
                    .uri("/internal/ai/v1/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AiClassificationResponse.class);
        } catch (RestClientResponseException e) {
            log.warn("AI service predict call failed with status {}: {}", e.getStatusCode(), e.getMessage());
            throw new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE, "AI classification failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.warn("AI service predict call failed: {}", e.getMessage());
            throw new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE, "AI inference service is unreachable", e);
        }
    }

    /**
     * Helper to classify an image from raw bytes by Base64-encoding it.
     */
    public AiClassificationResponse classify(String scanId, String patientCode, byte[] imageBytes) {
        String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
        return classify(new AiClassificationRequest(scanId, patientCode, base64));
    }

    /**
     * Invokes the internal AI service to perform U-Net segmentation on a brain MRI slice.
     *
     * @param request segmentation request payload
     * @return {@link AiSegmentationResponse} segmentation output
     */
    public AiSegmentationResponse segment(AiSegmentationRequest request) {
        try {
            return restClient.post()
                    .uri("/internal/ai/v1/segment")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AiSegmentationResponse.class);
        } catch (RestClientResponseException e) {
            log.warn("AI service segment call failed with status {}: {}", e.getStatusCode(), e.getMessage());
            throw new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE, "AI segmentation failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.warn("AI service segment call failed: {}", e.getMessage());
            throw new ApiException(ApiErrorCode.AI_SERVICE_UNAVAILABLE, "AI inference service is unreachable", e);
        }
    }

    /**
     * Helper to segment an image from raw bytes by Base64-encoding it.
     */
    public AiSegmentationResponse segment(String scanId, String patientCode, byte[] imageBytes) {
        String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
        return segment(new AiSegmentationRequest(scanId, patientCode, base64));
    }
}
