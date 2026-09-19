package com.braintwinx.explanation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates generated natural language explanations against the 10 Clinical Safety Prohibitions
 * defined in {@code docs/privacy-and-security/03-NEURO-AI-SAFETY-FRAMEWORK.md}.
 *
 * <p>Any violation results in immediate rejection and discarding of the generated text.
 */
@Component
public class ExplanationValidator {

    private static final Logger log = LoggerFactory.getLogger(ExplanationValidator.class);

    public record ValidationOutcome(boolean isValid, String rejectionReason) {
        public static ValidationOutcome pass() {
            return new ValidationOutcome(true, null);
        }

        public static ValidationOutcome fail(String reason) {
            return new ValidationOutcome(false, reason);
        }
    }

    // 4. Definitive Diagnostic Certainty
    private static final List<String> CERTAINTY_PHRASES = List.of(
            "definitely proves",
            "conclusive diagnosis",
            "conclusively proves",
            "100% verified",
            "absolute certainty",
            "confirms diagnosis of",
            "undeniably has",
            "unequivocally demonstrates"
    );

    // 5. Therapeutic or Prescription Directives
    private static final List<String> THERAPEUTIC_DIRECTIVES = List.of(
            "prescribe",
            "administer",
            "dosage",
            "mg/day",
            "recommend immediate surgical resection",
            "start chemotherapy",
            "radiation therapy recommended",
            "initiate temozolomide"
    );

    // 6. Visual Pixel Inspection Claims (Violates multimodal isolation)
    private static final List<String> VISUAL_PIXEL_CLAIMS = List.of(
            "looking at the mri",
            "visual inspection of the scan",
            "hyperintense signal on t2",
            "hypointense on t1",
            "contrast enhancement observed on slice",
            "upon visual inspection",
            "visible pixel density"
    );

    // 3. Hallucinated Clinical Symptoms or Patient History
    private static final List<String> HALLUCINATED_SYMPTOMS = List.of(
            "patient reports headache",
            "presents with seizures",
            "complaining of nausea",
            "history of syncope",
            "patient reports blurred vision",
            "previous resection in 20",
            "prior chemotherapy cycle"
    );

    // 8. Clinician Impersonation
    private static final List<String> CLINICIAN_IMPERSONATION = List.of(
            "as your doctor",
            "as your physician",
            "as your neurologist",
            "in my clinical practice",
            "i have examined the patient",
            "i diagnose the patient"
    );

    // 9. Mandatory Qualification Terms
    private static final List<String> MANDATORY_QUALIFICATION_TERMS = List.of(
            "decision support",
            "investigational",
            "assistive",
            "radiologist review",
            "clinical review",
            "verified by a qualified",
            "physician review"
    );

    /**
     * Validates generated text against the safety invariants and structured payload.
     */
    public ValidationOutcome validate(String text, ExplanationPayload payload) {
        if (text == null || text.isBlank()) {
            return ValidationOutcome.fail("PROHIBITION_10_EMPTY_TEXT: Explanation text is empty or blank");
        }

        String trimmed = text.trim();
        if (trimmed.length() < 30 || trimmed.length() > 4000) {
            return ValidationOutcome.fail("PROHIBITION_10_LENGTH_BOUNDS: Explanation length out of bounds (length=" + trimmed.length() + ")");
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);

        // Prohibition 4: Absolute Certainty
        for (String phrase : CERTAINTY_PHRASES) {
            if (lower.contains(phrase)) {
                log.warn("Explanation rejected: prohibited certainty phrase detected: '{}'", phrase);
                return ValidationOutcome.fail("PROHIBITION_4_DEFINITIVE_CERTAINTY: Contains definitive certainty claim '" + phrase + "'");
            }
        }

        // Prohibition 5: Therapeutic / Prescription Directives
        for (String phrase : THERAPEUTIC_DIRECTIVES) {
            if (lower.contains(phrase)) {
                log.warn("Explanation rejected: therapeutic directive detected: '{}'", phrase);
                return ValidationOutcome.fail("PROHIBITION_5_THERAPEUTIC_DIRECTIVE: Contains therapeutic prescription directive '" + phrase + "'");
            }
        }

        // Prohibition 6: Visual Pixel Inspection Claims (Multimodal Isolation)
        for (String phrase : VISUAL_PIXEL_CLAIMS) {
            if (lower.contains(phrase)) {
                log.warn("Explanation rejected: visual inspection claim detected: '{}'", phrase);
                return ValidationOutcome.fail("PROHIBITION_6_VISUAL_PIXEL_CLAIM: Violates multimodal isolation with claim '" + phrase + "'");
            }
        }

        // Prohibition 3: Hallucinated Clinical Symptoms
        for (String phrase : HALLUCINATED_SYMPTOMS) {
            if (lower.contains(phrase)) {
                log.warn("Explanation rejected: hallucinated symptom detected: '{}'", phrase);
                return ValidationOutcome.fail("PROHIBITION_3_HALLUCINATED_SYMPTOM: Mentions unverified clinical symptom '" + phrase + "'");
            }
        }

        // Prohibition 8: Clinician Impersonation
        for (String phrase : CLINICIAN_IMPERSONATION) {
            if (lower.contains(phrase)) {
                log.warn("Explanation rejected: clinician impersonation detected: '{}'", phrase);
                return ValidationOutcome.fail("PROHIBITION_8_CLINICIAN_IMPERSONATION: Impersonates clinician '" + phrase + "'");
            }
        }

        // Prohibition 1: Invented Anatomical Findings (Conflict with tumorType)
        if ("NO_TUMOR".equalsIgnoreCase(payload.tumorType())) {
            if (lower.contains("malignancy detected") || lower.contains("tumor present") || lower.contains("glioma identified") || lower.contains("meningioma identified")) {
                return ValidationOutcome.fail("PROHIBITION_1_INVENTED_FINDING: Claimed tumor presence when model output is NO_TUMOR");
            }
        } else {
            // If tumor is GLIOMA, should not claim it is MENINGIOMA or PITUITARY as the primary finding
            if ("GLIOMA".equalsIgnoreCase(payload.tumorType()) && lower.contains("primary finding is meningioma")) {
                return ValidationOutcome.fail("PROHIBITION_1_INVENTED_FINDING: Claimed meningioma when model output is GLIOMA");
            }
        }

        // Prohibition 7: Extrapolation on Insufficient Data
        boolean hasInsufficientHistory = payload.observationCount() == null
                || payload.observationCount() < 2
                || "INSUFFICIENT_HISTORY".equalsIgnoreCase(payload.trendDirection());
        if (hasInsufficientHistory) {
            if (lower.contains("rapid progression expected") || lower.contains("exponential growth predicted") || lower.contains("trajectory indicates doubling")) {
                return ValidationOutcome.fail("PROHIBITION_7_UNWARRANTED_EXTRAPOLATION: Extrapolated growth trajectory with insufficient longitudinal history");
            }
        }

        // Prohibition 9: Mandatory Qualification
        boolean hasQualification = false;
        for (String term : MANDATORY_QUALIFICATION_TERMS) {
            if (lower.contains(term)) {
                hasQualification = true;
                break;
            }
        }
        if (!hasQualification) {
            log.warn("Explanation rejected: missing mandatory qualification / assistive disclaimer");
            return ValidationOutcome.fail("PROHIBITION_9_MISSING_QUALIFICATION: Missing mandatory assistive decision-support qualification statement");
        }

        return ValidationOutcome.pass();
    }
}
