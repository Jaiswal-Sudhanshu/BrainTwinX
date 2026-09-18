package com.braintwinx.controller;

import com.braintwinx.dto.AnalysisJobResponse;
import com.braintwinx.dto.PredictionResponse;
import com.braintwinx.dto.ScanStatusResponse;
import com.braintwinx.security.JwtService;
import com.braintwinx.service.ClassificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing MRI tumor classification and job polling endpoints.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class ClassificationController {

    private static final String UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final ClassificationService classificationService;

    public ClassificationController(ClassificationService classificationService) {
        this.classificationService = classificationService;
    }

    /**
     * Triggers asynchronous MRI tumor classification.
     * Returns 202 Accepted on new job, or 200 OK if returning an existing job for the idempotency key.
     */
    @PostMapping("/scans/{scanPublicId}/analyze")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<AnalysisJobResponse> triggerAnalysis(
            @PathVariable
            @Pattern(regexp = UUID_REGEX, message = "is not a valid scan public ID")
            String scanPublicId,
            @RequestHeader(name = "Idempotency-Key", required = false)
            String idempotencyKey,
            @AuthenticationPrincipal
            JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest) {

        AnalysisJobResponse response = classificationService.triggerAnalysis(
                scanPublicId, idempotencyKey, principal, httpRequest);

        // If job was created fresh (progress == 0 and status == QUEUED), 202 Accepted; else 200 OK
        HttpStatus status = (response.progressPercent() == 0 && response.startedAt() == null)
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;

        return ResponseEntity.status(status).body(response);
    }

    /**
     * Synchronous classification endpoint for direct execution and integration verification.
     */
    @PostMapping("/scans/{scanPublicId}/classify")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<PredictionResponse> classifyDirect(
            @PathVariable
            @Pattern(regexp = UUID_REGEX, message = "is not a valid scan public ID")
            String scanPublicId,
            @AuthenticationPrincipal
            JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest) {

        PredictionResponse response = classificationService.classifyScanDirect(
                scanPublicId, principal, httpRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves the latest tumor prediction for a scan.
     */
    @GetMapping("/scans/{scanPublicId}/prediction")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<PredictionResponse> getPrediction(
            @PathVariable
            @Pattern(regexp = UUID_REGEX, message = "is not a valid scan public ID")
            String scanPublicId,
            @AuthenticationPrincipal
            JwtService.AuthenticatedPrincipal principal) {

        PredictionResponse response = classificationService.getPrediction(scanPublicId, principal);
        return ResponseEntity.ok(response);
    }

    /**
     * Polls current scan processing status and latest job progress.
     */
    @GetMapping("/scans/{scanPublicId}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<ScanStatusResponse> getScanStatus(
            @PathVariable
            @Pattern(regexp = UUID_REGEX, message = "is not a valid scan public ID")
            String scanPublicId,
            @AuthenticationPrincipal
            JwtService.AuthenticatedPrincipal principal) {

        ScanStatusResponse response = classificationService.getScanStatus(scanPublicId, principal);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves status of a specific analysis job by ID.
     */
    @GetMapping("/jobs/{jobPublicId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<AnalysisJobResponse> getJob(
            @PathVariable
            @Pattern(regexp = UUID_REGEX, message = "is not a valid job public ID")
            String jobPublicId,
            @AuthenticationPrincipal
            JwtService.AuthenticatedPrincipal principal) {

        AnalysisJobResponse response = classificationService.getJob(jobPublicId, principal);
        return ResponseEntity.ok(response);
    }
}
