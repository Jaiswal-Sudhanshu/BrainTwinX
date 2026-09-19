package com.braintwinx.explanation;

/**
 * Service provider interface for producing natural language decision-support summaries.
 */
public interface ExplanationProvider {

    /**
     * Generates an explanation for the given structured payload.
     *
     * <p>Providers must fail closed: if unconfigured or unavailable, return an
     * {@link ExplanationResult} with status {@code UNAVAILABLE} rather than raising
     * unhandled exceptions.
     */
    ExplanationResult generateExplanation(ExplanationPayload payload);

    /** Unique identifier for the provider (e.g., "STUB", "LLM", "OPENAI"). */
    String getProviderName();
}
