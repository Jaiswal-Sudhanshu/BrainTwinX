package com.braintwinx.report;

import com.braintwinx.entity.ExplanationStatus;
import com.braintwinx.entity.PatientSex;
import com.braintwinx.entity.ScanType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfReportRendererTest {

    private PdfReportRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new PdfReportRenderer();
    }

    @Test
    @DisplayName("Renders complete 2-page PDF report with all 11 sections")
    void rendersFullReportSuccessfully() throws Exception {
        ReportDataModel model = new ReportDataModel(
                "report-uuid-12345",
                Instant.now(),
                "dr_watson",
                "PT-88392",
                (short) 1975,
                PatientSex.MALE,
                "scan-uuid-9999",
                LocalDate.of(2026, 8, 15),
                ScanType.MRI_T1C,
                512,
                512,
                "Clinical evaluation for suspicious intracranial mass lesion.",
                "GLIOMA",
                new BigDecimal("0.965"),
                Map.of("GLIOMA", new BigDecimal("0.965"), "NO_TUMOR", new BigDecimal("0.035")),
                "BrainTumorCNN",
                "v1.0.0",
                true,
                1450L,
                224,
                224,
                "BrainTumorUNet",
                "v1.0.0",
                "COMPLETED",
                "GROWTH",
                3,
                65,
                new BigDecimal("14.50"),
                new BigDecimal("190.0"),
                "TumorGrowthLSTM",
                "v1.0.0",
                ExplanationStatus.INCLUDED,
                "Automated classification indicates GLIOMA. Volumetric segmentation delineates 1450 pixels lesion region. Radiologist review required.",
                "STUB",
                "DeterministicClinicalTemplate-v1.0",
                null,
                "1.0.0"
        );

        byte[] pdfBytes = renderer.render(model);

        assertThat(pdfBytes).isNotNull();
        assertThat(pdfBytes.length).isGreaterThan(1000);

        // Verify PDF Header
        String header = new String(pdfBytes, 0, 8);
        assertThat(header).startsWith("%PDF-");

        // Parse with PDFBox and verify extracted text contains all sections
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);

            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(document);

            // Check key contents
            assertThat(fullText).contains("BrainTwinX — Clinical Decision Support Summary");
            assertThat(fullText).contains("report-uuid-12345");
            assertThat(fullText).contains("PT-88392");
            assertThat(fullText).contains("GLIOMA");
            assertThat(fullText).contains("1450 pixels");
            assertThat(fullText).contains("GROWTH");
            assertThat(fullText).contains("Mandatory Regulatory & Clinical Safety Disclaimer");
            assertThat(fullText).contains("BrainTumorCNN");
            assertThat(fullText).contains("BrainTumorUNet");
            assertThat(fullText).contains("TumorGrowthLSTM");
            assertThat(fullText).contains("Reviewing Radiologist");
        }
    }

    @Test
    @DisplayName("Renders report with missing analytical stages as 'Not available' without throwing")
    void rendersReportWithMissingStagesSafely() throws Exception {
        ReportDataModel minimalModel = new ReportDataModel(
                "report-uuid-minimal",
                Instant.now(),
                "dr_house",
                "PT-00001",
                null,
                PatientSex.UNKNOWN,
                "scan-uuid-0000",
                null,
                null,
                null,
                null,
                null,
                null, // classification absent
                null,
                null,
                null,
                null,
                null, // segmentation absent
                null,
                null,
                null,
                null,
                null,
                null, // growth absent
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ExplanationStatus.UNAVAILABLE,
                null,
                null,
                null,
                "Provider unconfigured",
                "1.0.0"
        );

        byte[] pdfBytes = renderer.render(minimalModel);

        assertThat(pdfBytes).isNotNull();
        assertThat(pdfBytes.length).isGreaterThan(1000);

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);

            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(document);

            assertThat(fullText).contains("Not available");
            assertThat(fullText).contains("Explanation Not Available");
            assertThat(fullText).contains("Mandatory Regulatory & Clinical Safety Disclaimer");
        }
    }
}
