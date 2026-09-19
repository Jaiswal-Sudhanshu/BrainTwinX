package com.braintwinx.report;

import com.braintwinx.entity.ExplanationStatus;
import com.braintwinx.entity.PatientSex;
import com.braintwinx.entity.ScanType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Immutable aggregated data model carrying all data points required for rendering
 * the 11 standard sections of the clinical decision-support PDF report.
 */
public record ReportDataModel(
        // 1. Report Metadata
        String reportPublicId,
        Instant generatedAt,
        String generatedByUsername,

        // 2. Patient Demographics (De-identified Safe Harbor § 164.514(b)(2))
        String patientCode,
        Short birthYear,
        PatientSex sex,

        // 3. Scan Acquisition Context
        String scanPublicId,
        LocalDate scanDate,
        ScanType scanType,
        Integer imageWidth,
        Integer imageHeight,

        // 4. Clinical Indication
        String clinicalIndication,

        // 5. AI Classification Findings (Nullable if stage absent)
        String predictedClass,
        BigDecimal classificationConfidence,
        Map<String, BigDecimal> classProbabilities,
        String classifierModelName,
        String classifierModelVersion,

        // 6. AI Tumour Segmentation Findings (Nullable if stage absent)
        Boolean tumorDetected,
        Long tumorAreaPx,
        Integer maskWidth,
        Integer maskHeight,
        String segmenterModelName,
        String segmenterModelVersion,

        // 7. Longitudinal Trajectory Findings (Nullable if stage absent)
        String growthStatus,
        String trendDirection,
        Integer observationCount,
        Integer spanDays,
        BigDecimal growthRateMm2PerMonth,
        BigDecimal doublingTimeDays,
        String forecasterModelName,
        String forecasterModelVersion,

        // 8. Decision-Support Explanation & Synthesis
        ExplanationStatus explanationStatus,
        String explanationText,
        String explanationProvider,
        String explanationModel,
        String explanationRejectionReason,

        // 9. Technical Preprocessing Provenance
        String preprocessingVersion
) {}
