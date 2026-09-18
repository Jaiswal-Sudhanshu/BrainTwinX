package com.braintwinx.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.braintwinx.config.AiClientProperties;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("AiServiceClient")
class AiServiceClientTest {

    private MockRestServiceServer mockServer;
    private AiServiceClient aiServiceClient;
    private AiClientProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AiClientProperties();
        properties.setBaseUrl("http://ai-service.test");
        properties.setTimeoutMs(5000);
        properties.setApiKey("test-secret-key-123");

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        aiServiceClient = new AiServiceClient(properties, builder, false);
    }

    @Test
    @DisplayName("checkLiveness returns ok when AI service is healthy")
    void checkLivenessReturnsOk() {
        mockServer.expect(requestTo("http://ai-service.test/internal/ai/v1/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"ok","service":"braintwinx-ai-service","timestamp":"2026-08-20T10:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        AiHealthResponse response = aiServiceClient.checkLiveness();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.service()).isEqualTo("braintwinx-ai-service");
        mockServer.verify();
    }

    @Test
    @DisplayName("checkLiveness throws AI_SERVICE_UNAVAILABLE on connection failure or 500")
    void checkLivenessThrowsUnavailableOnServerError() {
        mockServer.expect(requestTo("http://ai-service.test/internal/ai/v1/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> aiServiceClient.checkLiveness())
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ApiErrorCode.AI_SERVICE_UNAVAILABLE));

        mockServer.verify();
    }

    @Test
    @DisplayName("checkReadiness parses 200 ready response and sends internal API key header")
    void checkReadinessParsesReadyResponseWithApiKey() {
        mockServer.expect(requestTo("http://ai-service.test/internal/ai/v1/ready"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-API-Key", "test-secret-key-123"))
                .andRespond(withSuccess("""
                        {
                            "ready": true,
                            "preprocessingVersion": "1.0.0",
                            "modelsLoaded": {"classifier": true, "segmentation": true},
                            "reason": null,
                            "timestamp": "2026-08-20T10:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        AiReadyResponse response = aiServiceClient.checkReadiness();

        assertThat(response).isNotNull();
        assertThat(response.ready()).isTrue();
        assertThat(response.preprocessingVersion()).isEqualTo("1.0.0");
        mockServer.verify();
    }

    @Test
    @DisplayName("checkReadiness returns unready response on 503 without crashing")
    void checkReadinessHandles503Gracefully() {
        mockServer.expect(requestTo("http://ai-service.test/internal/ai/v1/ready"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                        {
                            "ready": false,
                            "preprocessingVersion": "1.0.0",
                            "modelsLoaded": {"classifier": false},
                            "reason": "WEIGHTS_MISSING",
                            "timestamp": "2026-08-20T10:00:00Z"
                        }
                        """));

        AiReadyResponse response = aiServiceClient.checkReadiness();

        assertThat(response).isNotNull();
        assertThat(response.ready()).isFalse();
        assertThat(response.reason()).isEqualTo("WEIGHTS_MISSING");
        mockServer.verify();
    }

    @Test
    @DisplayName("ensureReady throws AI_SERVICE_UNAVAILABLE when service reports unready")
    void ensureReadyThrowsWhenUnready() {
        mockServer.expect(requestTo("http://ai-service.test/internal/ai/v1/ready"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                        {
                            "ready": false,
                            "preprocessingVersion": "1.0.0",
                            "modelsLoaded": {"classifier": false},
                            "reason": "WEIGHTS_MISSING",
                            "timestamp": "2026-08-20T10:00:00Z"
                        }
                        """));

        assertThatThrownBy(() -> aiServiceClient.ensureReady())
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getErrorCode()).isEqualTo(ApiErrorCode.AI_SERVICE_UNAVAILABLE);
                    assertThat(ex.getMessage()).contains("WEIGHTS_MISSING");
                });

        mockServer.verify();
    }
}
