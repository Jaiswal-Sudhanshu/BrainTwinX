package com.braintwinx.controller;

import com.braintwinx.dto.PageResponse;
import com.braintwinx.dto.PatientCreateRequest;
import com.braintwinx.dto.PatientResponse;
import com.braintwinx.dto.PatientUpdateRequest;
import com.braintwinx.entity.PatientStatus;
import com.braintwinx.security.JwtService;
import com.braintwinx.service.PatientService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Patient management endpoints (project brief section 17).
 *
 * <p>Thin by design (brief section 49): validate, delegate, shape the response. Every access-scope
 * decision lives in {@link PatientService}, so it holds regardless of how the operation is reached.
 * The {@code @PreAuthorize} annotations here are a coarse first gate on role, not the authorisation
 * itself — a caller who passes them can still only see records within their scope.
 *
 * <p>Resources are addressed by {@code patientCode} throughout. The internal sequential id never
 * appears in a URL (brief sections 8 and 26).
 *
 * <p>The path variable is pattern-constrained rather than trusted. Without that, a code containing
 * path separators or control characters would reach the service and, later, log lines and audit
 * rows.
 */
@RestController
@RequestMapping("/api/v1/patients")
@Validated
public class PatientController {

    /** Bounded so a caller cannot request an unbounded page and bulk-extract the table. */
    private static final int MAX_PAGE_SIZE = 100;

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    /**
     * Creates a patient record.
     *
     * @return 201 with a {@code Location} header, or 409 if the code is taken
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<PatientResponse> create(
            @Valid @RequestBody PatientCreateRequest request,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest) {

        PatientResponse created = patientService.create(request, principal, httpRequest);

        // Location addresses the resource by its public-safe code, never an internal id.
        var location = UriComponentsBuilder.fromPath("/api/v1/patients/{patientCode}")
                .buildAndExpand(created.patientCode())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Reads one patient.
     *
     * @return 200, or 404 if absent <em>or</em> outside the caller's scope — the two are
     *         deliberately indistinguishable
     */
    @GetMapping("/{patientCode}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<PatientResponse> get(
            @PathVariable
            @Size(max = 32, message = "must be at most 32 characters")
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*", message = "is not a valid patient code")
            String patientCode,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest) {

        return ResponseEntity.ok(patientService.get(patientCode, principal, httpRequest));
    }

    /**
     * Lists patients visible to the caller.
     *
     * <p>Defaults to {@code ACTIVE} so archived records are excluded by choice rather than by
     * accident, and sorts newest first to match the index that serves this query.
     *
     * @param status which lifecycle state to list; defaults to {@code ACTIVE}
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'RESEARCHER')")
    public ResponseEntity<PageResponse<PatientResponse>> list(
            @RequestParam(required = false) PatientStatus status,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "must be 0 or greater") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "must be at least 1")
            @Max(value = MAX_PAGE_SIZE, message = "must be at most 100") int size,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal) {

        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(patientService.list(status, pageable, principal));
    }

    /**
     * Updates the mutable fields of a patient record.
     *
     * <p>A partial update: fields omitted from the body are left unchanged rather than nulled.
     *
     * @return 200, 404 if absent or out of scope, or 409 if the record is archived
     */
    @PutMapping("/{patientCode}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<PatientResponse> update(
            @PathVariable
            @Size(max = 32, message = "must be at most 32 characters")
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*", message = "is not a valid patient code")
            String patientCode,
            @Valid @RequestBody PatientUpdateRequest request,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest) {

        return ResponseEntity.ok(
                patientService.update(patientCode, request, principal, httpRequest));
    }

    /**
     * Archives a patient record.
     *
     * <p>Mapped to {@code DELETE} for REST convention, but performs a <strong>soft archive</strong>
     * (ASSUMPTIONS.md A-13). No hard delete is exposed anywhere: predictions, reports, and audit
     * entries reference the patient, and destroying it would orphan the audit trail that brief
     * section 27 requires be preserved.
     *
     * <p>Idempotent — archiving twice is not an error.
     *
     * @return 204
     */
    @DeleteMapping("/{patientCode}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<Void> archive(
            @PathVariable
            @Size(max = 32, message = "must be at most 32 characters")
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*", message = "is not a valid patient code")
            String patientCode,
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest) {

        patientService.archive(patientCode, principal, httpRequest);
        return ResponseEntity.noContent().build();
    }
}
