package com.braintwinx.controller;

import com.braintwinx.dto.PageResponse;
import com.braintwinx.dto.ScanResponse;
import com.braintwinx.entity.ScanType;
import com.braintwinx.security.JwtService;
import com.braintwinx.service.ScanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Controller exposing MRI scan upload, status retrieval, and image download endpoints.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class ScanController {

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    /**
     * Uploads an MRI scan for a patient.
     */
    @PostMapping(value = "/patients/{patientCode}/scans", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<ScanResponse> uploadScan(
            @PathVariable
            @Size(max = 32, message = "must be at most 32 characters")
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*", message = "is not a valid patient code")
            String patientCode,
            @RequestParam("scanDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate scanDate,
            @RequestParam("scanType")
            ScanType scanType,
            @RequestParam("file")
            MultipartFile file,
            @AuthenticationPrincipal
            JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest) {

        ScanResponse response = scanService.uploadScan(patientCode, scanDate, scanType, file, principal, httpRequest);

        URI location = UriComponentsBuilder.fromPath("/api/v1/scans/{publicId}")
                .buildAndExpand(response.publicId())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    /**
     * Retrieves metadata for a single scan.
     */
    @GetMapping("/scans/{publicId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<ScanResponse> getScan(
            @PathVariable
            @Size(max = 36, message = "must be at most 36 characters")
            String publicId,
            @AuthenticationPrincipal
            JwtService.AuthenticatedPrincipal principal) {

        return ResponseEntity.ok(scanService.getScan(publicId, principal));
    }

    /**
     * Downloads the raw scan image payload.
     */
    @GetMapping("/scans/{publicId}/image")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<Resource> downloadImage(
            @PathVariable
            @Size(max = 36, message = "must be at most 36 characters")
            String publicId,
            @AuthenticationPrincipal
            JwtService.AuthenticatedPrincipal principal) {

        ScanService.ScanFileData fileData = scanService.loadScanImage(publicId, principal);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fileData.mimeType()))
                .contentLength(fileData.contentLength())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileData.originalFilename() + "\"")
                .body(fileData.resource());
    }

    /**
     * Lists scans for a given patient with pagination.
     */
    @GetMapping("/patients/{patientCode}/scans")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<PageResponse<ScanResponse>> listPatientScans(
            @PathVariable
            @Size(max = 32, message = "must be at most 32 characters")
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*", message = "is not a valid patient code")
            String patientCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal
            JwtService.AuthenticatedPrincipal principal) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "scanDate"));
        return ResponseEntity.ok(scanService.listScansForPatient(patientCode, pageRequest, principal));
    }
}
