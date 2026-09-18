package com.braintwinx.client;

import java.time.Instant;

/**
 * Liveness response payload from the internal AI service.
 */
public record AiHealthResponse(
        String status,
        String service,
        Instant timestamp
) {}
