package com.braintwinx.explanation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplanationValidatorTest {

    private ExplanationValidator validator;
    private ExplanationPayload standardPayload;

    @BeforeEach
    void setUp() {
        validator = new ExplanationValidator();
        standardPayload = new ExplanationPayload(
                "scan-uuid-1",
                "PT-1001",
                "GLIOMA",
                0.94,
                1420L,
                "GROWTH",
                3,
                65,
                "BrainTumorCNN v1.0.0",
                "BrainTumorUNet v1.0.0",
                "TumorGrowthLSTM v1.0.0"
        );
    }

    @Test
    @DisplayName("Valid explanation passing all safety criteria is accepted")
    void validExplanationPasses() {
        String text = "Automated model output indicates features consistent with GLIOMA. "
                + "Estimated lesion area is 1420 pixels (tumorAreaPx). "
                + "Assistive notice: This is computational decision support and requires radiologist review.";

        ExplanationValidator.ValidationOutcome outcome = validator.validate(text, standardPayload);
        assertThat(outcome.isValid()).isTrue();
        assertThat(outcome.rejectionReason()).isNull();
    }

    @Test
    @DisplayName("Prohibition 1: Rejects invented tumor presence when payload is NO_TUMOR")
    void rejectsInventedFindingsWhenNoTumor() {
        ExplanationPayload noTumorPayload = new ExplanationPayload(
                "scan-uuid-2",
                "PT-1002",
                "NO_TUMOR",
                0.98,
                null,
                null,
                1,
                null,
                "BrainTumorCNN v1.0.0",
                "BrainTumorUNet v1.0.0",
                null
        );
        String text = "Evaluation shows malignancy detected in the parietal lobe. "
                + "Assistive decision support for clinical review.";

        ExplanationValidator.ValidationOutcome outcome = validator.validate(text, noTumorPayload);
        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.rejectionReason()).contains("PROHIBITION_1_INVENTED_FINDING");
    }

    @Test
    @DisplayName("Prohibition 3: Rejects hallucinated clinical symptoms")
    void rejectsHallucinatedSymptoms() {
        String text = "Patient presents with seizures and worsening headache. "
                + "Findings support GLIOMA. Assistive decision support requiring radiologist review.";

        ExplanationValidator.ValidationOutcome outcome = validator.validate(text, standardPayload);
        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.rejectionReason()).contains("PROHIBITION_3_HALLUCINATED_SYMPTOM");
    }

    @Test
    @DisplayName("Prohibition 4: Rejects definitive certainty claims")
    void rejectsDefinitiveCertainty() {
        String text = "This scan definitely proves the patient has an aggressive tumor. "
                + "Assistive decision support for clinical review.";

        ExplanationValidator.ValidationOutcome outcome = validator.validate(text, standardPayload);
        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.rejectionReason()).contains("PROHIBITION_4_DEFINITIVE_CERTAINTY");
    }

    @Test
    @DisplayName("Prohibition 5: Rejects therapeutic and prescription directives")
    void rejectsTherapeuticDirectives() {
        String text = "Recommend immediate surgical resection and administer chemotherapy. "
                + "Assistive decision support for radiologist review.";

        ExplanationValidator.ValidationOutcome outcome = validator.validate(text, standardPayload);
        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.rejectionReason()).contains("PROHIBITION_5_THERAPEUTIC_DIRECTIVE");
    }

    @Test
    @DisplayName("Prohibition 6: Rejects claims of visual inspection (Multimodal Isolation)")
    void rejectsVisualInspectionClaims() {
        String text = "Looking at the MRI image, hyperintense signal on T2 slice confirms the region. "
                + "Assistive decision support for clinical review.";

        ExplanationValidator.ValidationOutcome outcome = validator.validate(text, standardPayload);
        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.rejectionReason()).contains("PROHIBITION_6_VISUAL_PIXEL_CLAIM");
    }

    @Test
    @DisplayName("Prohibition 7: Rejects extrapolation when longitudinal history is insufficient")
    void rejectsExtrapolationOnInsufficientHistory() {
        ExplanationPayload singleScanPayload = new ExplanationPayload(
                "scan-uuid-3",
                "PT-1003",
                "GLIOMA",
                0.90,
                500L,
                "INSUFFICIENT_HISTORY",
                1,
                null,
                "BrainTumorCNN v1.0.0",
                "BrainTumorUNet v1.0.0",
                null
        );
        String text = "Findings show glioma lesion. Rapid progression expected over coming weeks. "
                + "Assistive decision support requiring radiologist review.";

        ExplanationValidator.ValidationOutcome outcome = validator.validate(text, singleScanPayload);
        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.rejectionReason()).contains("PROHIBITION_7_UNWARRANTED_EXTRAPOLATION");
    }

    @Test
    @DisplayName("Prohibition 8: Rejects clinician impersonation")
    void rejectsClinicianImpersonation() {
        String text = "As your doctor, I recommend you discuss these glioma findings promptly. "
                + "Assistive decision support for clinical review.";

        ExplanationValidator.ValidationOutcome outcome = validator.validate(text, standardPayload);
        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.rejectionReason()).contains("PROHIBITION_8_CLINICIAN_IMPERSONATION");
    }

    @Test
    @DisplayName("Prohibition 9: Rejects explanation missing mandatory qualification disclaimer")
    void rejectsMissingQualificationDisclaimer() {
        String text = "Automated model output indicates features consistent with GLIOMA. "
                + "Estimated lesion area is 1420 pixels in size without further comment.";

        ExplanationValidator.ValidationOutcome outcome = validator.validate(text, standardPayload);
        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.rejectionReason()).contains("PROHIBITION_9_MISSING_QUALIFICATION");
    }

    @Test
    @DisplayName("Prohibition 10: Rejects empty text")
    void rejectsEmptyText() {
        ExplanationValidator.ValidationOutcome outcome = validator.validate("", standardPayload);
        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.rejectionReason()).contains("PROHIBITION_10_EMPTY_TEXT");
    }
}
