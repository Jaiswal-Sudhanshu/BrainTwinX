package com.braintwinx.explanation;

import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Deterministic, clinically conservative decision-support explanation provider.
 *
 * <p>Always produces compliant summaries grounded strictly in the verified payload,
 * ensuring seamless fallback when third-party LLM providers are offline or unconfigured.
 */
@Component
public class StubExplanationProvider implements ExplanationProvider {

    public static final String PROVIDER_NAME = "STUB_EXPLANATION_ENGINE";
    public static final String MODEL_NAME = "DeterministicClinicalTemplate-v1.0";

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public ExplanationResult generateExplanation(ExplanationPayload payload) {
        StringBuilder sb = new StringBuilder();

        // 1. Classification finding
        double confPercent = payload.confidence() != null ? payload.confidence() * 100.0 : 0.0;
        sb.append(String.format(
                Locale.ROOT,
                "AI Decision Support Summary: Automated classification model (%s) evaluated the scan and indicated a profile consistent with %s with %.1f%% model confidence. ",
                payload.classifierModel() != null ? payload.classifierModel() : "Standard Classifier",
                payload.tumorType(),
                confPercent
        ));

        // 2. Segmentation finding
        if (payload.tumorAreaPx() != null && payload.tumorAreaPx() > 0) {
            sb.append(String.format(
                    Locale.ROOT,
                    "Segmentation analysis (%s) delineated a contiguous lesion region with an estimated area of %d pixels (tumorAreaPx). ",
                    payload.segmenterModel() != null ? payload.segmenterModel() : "Standard Segmenter",
                    payload.tumorAreaPx()
            ));
        } else if ("NO_TUMOR".equalsIgnoreCase(payload.tumorType())) {
            sb.append("No abnormal hyperintense lesion boundary was segmented. ");
        }

        // 3. Longitudinal finding
        if (payload.trendDirection() != null) {
            if ("INSUFFICIENT_HISTORY".equalsIgnoreCase(payload.trendDirection())) {
                sb.append("Longitudinal progression modeling deferred: baseline single scan, insufficient history for trajectory modeling. ");
            } else {
                sb.append(String.format(
                        Locale.ROOT,
                        "Longitudinal trajectory model (%s) evaluated %d scan timepoint(s) spanning %s days and categorized the growth dynamic as %s. ",
                        payload.forecasterModel() != null ? payload.forecasterModel() : "Standard Forecaster",
                        payload.observationCount() != null ? payload.observationCount() : 1,
                        payload.spanDays() != null ? payload.spanDays() : 0,
                        payload.trendDirection()
                ));
            }
        }

        // 4. Mandatory qualification
        sb.append("Assistive notice: These findings constitute computational decision support and do not represent a final diagnosis. Radiologist review and correlation with patient medical history is required.");

        return ExplanationResult.included(sb.toString(), PROVIDER_NAME, MODEL_NAME);
    }
}
