package com.braintwinx.controller;

import com.braintwinx.dto.GrowthPredictionResponse;
import com.braintwinx.dto.PageResponse;
import com.braintwinx.security.JwtService;
import com.braintwinx.service.GrowthPredictionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
public class GrowthPredictionController {

    private final GrowthPredictionService growthPredictionService;

    public GrowthPredictionController(GrowthPredictionService growthPredictionService) {
        this.growthPredictionService = growthPredictionService;
    }

    /**
     * Triggers longitudinal growth analysis for a patient.
     * Enforces that history is evaluated; returns INSUFFICIENT_HISTORY if under threshold.
     */
    @PostMapping("/patients/{patientCode}/growth-analysis")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<GrowthPredictionResponse> analyzeGrowth(
            @PathVariable @NotBlank String patientCode,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        GrowthPredictionResponse response = growthPredictionService.analyzeGrowth(patientCode, principal, httpRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves the latest growth prediction for a patient.
     */
    @GetMapping("/patients/{patientCode}/growth-analysis")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<GrowthPredictionResponse> getLatestGrowth(
            @PathVariable @NotBlank String patientCode,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal
    ) {
        GrowthPredictionResponse response = growthPredictionService.getLatestGrowthPrediction(patientCode, principal);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves paginated historical growth predictions for a patient.
     */
    @GetMapping("/patients/{patientCode}/growth-analysis/history")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<PageResponse<GrowthPredictionResponse>> getGrowthHistory(
            @PathVariable @NotBlank String patientCode,
            @PageableDefault(size = 10) Pageable pageable,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal
    ) {
        PageResponse<GrowthPredictionResponse> response = growthPredictionService.getGrowthPredictionHistory(patientCode, pageable, principal);
        return ResponseEntity.ok(response);
    }
}
