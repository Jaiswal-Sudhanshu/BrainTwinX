package com.braintwinx.controller;

import com.braintwinx.dto.SegmentationResponse;
import com.braintwinx.security.JwtService;
import com.braintwinx.service.SegmentationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.io.InputStream;
import org.springframework.core.io.InputStreamResource;
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

@RestController
@RequestMapping("/api/v1")
@Validated
public class SegmentationController {

    private static final String UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final SegmentationService segmentationService;

    public SegmentationController(SegmentationService segmentationService) {
        this.segmentationService = segmentationService;
    }

    /**
     * Executes synchronous U-Net segmentation on an MRI scan.
     */
    @PostMapping("/scans/{scanPublicId}/segment")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<SegmentationResponse> segmentScan(
            @PathVariable
            @Pattern(regexp = UUID_REGEX, message = "is not a valid scan public ID")
            String scanPublicId,
            @AuthenticationPrincipal
            JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        SegmentationResponse response = segmentationService.segmentScanDirect(scanPublicId, principal, httpRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves the latest segmentation metadata and bounding box for a scan.
     */
    @GetMapping("/scans/{scanPublicId}/segmentation")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<SegmentationResponse> getSegmentation(
            @PathVariable
            @Pattern(regexp = UUID_REGEX, message = "is not a valid scan public ID")
            String scanPublicId,
            @AuthenticationPrincipal
            JwtService.AuthenticatedPrincipal principal
    ) {
        SegmentationResponse response = segmentationService.getSegmentation(scanPublicId, principal);
        return ResponseEntity.ok(response);
    }

    /**
     * Downloads the generated binary mask PNG file artefact.
     */
    @GetMapping(value = "/scans/{scanPublicId}/mask", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<InputStreamResource> getMaskFile(
            @PathVariable
            @Pattern(regexp = UUID_REGEX, message = "is not a valid scan public ID")
            String scanPublicId,
            @AuthenticationPrincipal
            JwtService.AuthenticatedPrincipal principal
    ) {
        InputStream maskStream = segmentationService.loadMask(scanPublicId, principal);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"mask_" + scanPublicId + ".png\"")
                .contentType(MediaType.IMAGE_PNG)
                .body(new InputStreamResource(maskStream));
    }
}
