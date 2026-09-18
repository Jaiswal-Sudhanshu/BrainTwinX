package com.braintwinx.client;

import java.time.Instant;
import java.util.Map;

/**
 * Readiness response payload from the internal AI service.
 */
public record AiReadyResponse(
        boolean ready,
        String preprocessingVersion,
        Map<String, Boolean> modelsLoaded,
        String reason,
        Instant timestamp
) {}
