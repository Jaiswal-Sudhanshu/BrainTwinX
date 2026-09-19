package com.braintwinx.explanation;

/**
 * Allow-listed, strictly typed clinical and AI inference payload passed to explanation generators.
 *
 * <p><strong>CRITICAL MEDICAL SAFETY INVARIANT (Multimodal Isolation):</strong>
 * Image bytes, raw pixel arrays, and DICOM binaries are strictly excluded from this payload.
 * Explanation generators receive only verified, structured outputs from prior deterministic
 * analytical stages to eliminate hallucinatory visual interpretations.
 */
public record ExplanationPayload(
        String scanPublicId,
        String patientCode,
        String tumorType,
        Double confidence,
        Long tumorAreaPx,
        String trendDirection,
        Integer observationCount,
        Integer spanDays,
        String classifierModel,
        String segmenterModel,
        String forecasterModel
) {
    public ExplanationPayload {
        if (scanPublicId == null || scanPublicId.isBlank()) {
            throw new IllegalArgumentException("scanPublicId must not be null or blank");
        }
        if (patientCode == null || patientCode.isBlank()) {
            throw new IllegalArgumentException("patientCode must not be null or blank");
        }
        if (tumorType == null || tumorType.isBlank()) {
            throw new IllegalArgumentException("tumorType must not be null or blank");
        }
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
    }
}
