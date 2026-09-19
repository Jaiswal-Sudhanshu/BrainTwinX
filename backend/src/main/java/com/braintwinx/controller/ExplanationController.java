package com.braintwinx.controller;

import com.braintwinx.dto.ExplanationResponse;
import com.braintwinx.security.JwtService;
import com.braintwinx.service.ExplanationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for generating AI decision-support explanations (Phase 10).
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class ExplanationController {

    private final ExplanationService explanationService;

    public ExplanationController(ExplanationService explanationService) {
        this.explanationService = explanationService;
    }

    /**
     * Generates a clinically verified decision-support explanation for a scan.
     * Fails closed if the explanation violates safety criteria.
     */
    @PostMapping("/scans/{scanPublicId}/explanation")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<ExplanationResponse> generateExplanation(
            @PathVariable @NotBlank String scanPublicId,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        ExplanationResponse response = explanationService.generateExplanation(scanPublicId, principal, httpRequest);
        return ResponseEntity.ok(response);
    }
}
