package com.braintwinx.controller;

import com.braintwinx.dto.PageResponse;
import com.braintwinx.dto.ReportResponse;
import com.braintwinx.security.JwtService;
import com.braintwinx.service.ReportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for clinical decision-support report generation and retrieval (Phase 11).
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Generates a new tamper-evident clinical decision-support PDF report for a scan.
     */
    @PostMapping("/scans/{scanPublicId}/reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<ReportResponse> generateReport(
            @PathVariable @NotBlank String scanPublicId,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        ReportResponse response = reportService.generateReport(scanPublicId, principal, httpRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves metadata for a generated report.
     */
    @GetMapping("/reports/{reportPublicId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<ReportResponse> getReport(
            @PathVariable @NotBlank String reportPublicId,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal
    ) {
        ReportResponse response = reportService.getReport(reportPublicId, principal);
        return ResponseEntity.ok(response);
    }

    /**
     * Streams the PDF report binary with SHA-256 integrity verification.
     */
    @GetMapping("/reports/{reportPublicId}/download")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<Resource> downloadReport(
            @PathVariable @NotBlank String reportPublicId,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        ReportService.DownloadResult result = reportService.downloadReport(reportPublicId, principal, httpRequest);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(result.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
                .body(result.resource());
    }

    /**
     * Retrieves paginated reports issued for a given patient.
     */
    @GetMapping("/patients/{patientCode}/reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<PageResponse<ReportResponse>> getPatientReports(
            @PathVariable @NotBlank String patientCode,
            @PageableDefault(size = 10) Pageable pageable,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal
    ) {
        PageResponse<ReportResponse> response = reportService.getPatientReports(patientCode, pageable, principal);
        return ResponseEntity.ok(response);
    }
}
