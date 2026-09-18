package com.braintwinx.service;

import com.braintwinx.audit.AuditService;
import com.braintwinx.dto.PageResponse;
import com.braintwinx.dto.ScanResponse;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.ScanType;
import com.braintwinx.entity.User;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import com.braintwinx.exception.DuplicateResourceException;
import com.braintwinx.exception.ResourceNotFoundException;
import com.braintwinx.mapper.ScanMapper;
import com.braintwinx.repository.PatientRepository;
import com.braintwinx.repository.ScanRepository;
import com.braintwinx.repository.UserRepository;
import com.braintwinx.security.JwtService;
import com.braintwinx.validation.ImageValidationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service orchestrating MRI scan uploads, validation, storage, and retrieval.
 *
 * <p><strong>Access scope & IDOR defense (ADR-007):</strong>
 * <ul>
 *   <li>ADMIN has unrestricted access across all patients and scans.</li>
 *   <li>DOCTOR is restricted to patients within their own caseload. Attempting to upload to
 *       or view a scan belonging to another clinician returns 404, never 403.</li>
 *   <li>RESEARCHER has read-only access and cannot upload scans (returns 403).</li>
 * </ul>
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private static final String RESOURCE_TYPE = "SCAN";

    private final ScanRepository scanRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final ImageValidationService imageValidationService;
    private final ScanMapper scanMapper;
    private final AuditService auditService;

    public ScanService(ScanRepository scanRepository,
                       PatientRepository patientRepository,
                       UserRepository userRepository,
                       StorageService storageService,
                       ImageValidationService imageValidationService,
                       ScanMapper scanMapper,
                       AuditService auditService) {
        this.scanRepository = scanRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.imageValidationService = imageValidationService;
        this.scanMapper = scanMapper;
        this.auditService = auditService;
    }

    /**
     * Ingests and stores a new MRI scan for a patient.
     *
     * @param patientCode the public patient code
     * @param scanDate acquisition date
     * @param scanType sequence type
     * @param file the uploaded multipart file
     * @param principal authenticated user
     * @param httpRequest HTTP servlet request for audit context
     * @return {@link ScanResponse} public representation
     */
    @Transactional
    public ScanResponse uploadScan(String patientCode,
                                   LocalDate scanDate,
                                   ScanType scanType,
                                   MultipartFile file,
                                   JwtService.AuthenticatedPrincipal principal,
                                   HttpServletRequest httpRequest) {
        User caller = requireCaller(principal);
        requireWriteAccess(caller);

        Patient patient = loadPatientInScope(patientCode, caller);

        if (!patient.isActive()) {
            throw new ApiException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot upload scan for archived patient: " + patientCode);
        }

        if (scanDate == null) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST, "Scan date is required");
        }
        if (scanType == null) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST, "Scan type is required");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(ApiErrorCode.INVALID_FILE, "Uploaded file is empty");
        }

        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (Exception e) {
            log.error("Failed to read upload multipart bytes", e);
            throw new ApiException(ApiErrorCode.INVALID_FILE, "Failed to read upload payload", e);
        }

        ImageValidationService.ValidatedImage validated;
        try {
            validated = imageValidationService.validate(rawBytes, file.getOriginalFilename(), file.getContentType());
        } catch (ApiException e) {
            auditService.record(caller, caller.getUsername(), AuditAction.SCAN_VALIDATION_FAILED,
                    RESOURCE_TYPE, patientCode, false,
                    Map.of("reason", e.getErrorCode().name()), httpRequest);
            throw e;
        }

        // Duplicate-upload check for this patient
        if (scanRepository.existsByPatientAndContentSha256(patient, validated.contentSha256())) {
            auditService.record(caller, caller.getUsername(), AuditAction.SCAN_VALIDATION_FAILED,
                    RESOURCE_TYPE, patientCode, false,
                    Map.of("reason", "DUPLICATE_SCAN"), httpRequest);
            throw new DuplicateResourceException(
                    "An identical scan file has already been uploaded for patient: " + patientCode);
        }

        String storageKey = storageService.generateScanStorageKey(validated.extension());
        storageService.store(new ByteArrayInputStream(validated.bytes()), storageKey);

        Scan scan = new Scan(
                patient,
                scanDate,
                scanType,
                storageKey,
                validated.detectedMimeType(),
                validated.fileSizeBytes(),
                validated.contentSha256(),
                caller
        );
        scan.setOriginalFilename(validated.sanitizedFilename());
        scan.recordDimensions(validated.imageWidth(), validated.imageHeight());

        Scan saved = scanRepository.save(scan);
        log.info("Scan {} uploaded for patient {}", saved.getPublicId(), patient.getPatientCode());

        auditService.record(caller, caller.getUsername(), AuditAction.SCAN_UPLOADED,
                RESOURCE_TYPE, saved.getPublicId(), true,
                Map.of("patientCode", patient.getPatientCode(),
                        "sha256", saved.getContentSha256(),
                        "sizeBytes", String.valueOf(saved.getFileSizeBytes())),
                httpRequest);

        return scanMapper.toResponse(saved);
    }

    /**
     * Reads metadata for a scan by public ID.
     */
    @Transactional(readOnly = true)
    public ScanResponse getScan(String publicId,
                                JwtService.AuthenticatedPrincipal principal) {
        User caller = requireCaller(principal);
        Scan scan = loadScanInScope(publicId, caller);
        return scanMapper.toResponse(scan);
    }

    /**
     * Lists scans for a patient, ordered by scan date descending.
     */
    @Transactional(readOnly = true)
    public PageResponse<ScanResponse> listScansForPatient(String patientCode,
                                                          Pageable pageable,
                                                          JwtService.AuthenticatedPrincipal principal) {
        User caller = requireCaller(principal);
        Patient patient = loadPatientInScope(patientCode, caller);
        Page<Scan> page = scanRepository.findByPatientOrderByScanDateDesc(patient, pageable);
        return PageResponse.from(page.map(scanMapper::toResponse));
    }

    public record ScanFileData(Resource resource, String mimeType, long contentLength, String originalFilename) {}

    /**
     * Loads the raw scan image data stream.
     */
    @Transactional(readOnly = true)
    public ScanFileData loadScanImage(String publicId,
                                      JwtService.AuthenticatedPrincipal principal) {
        User caller = requireCaller(principal);
        Scan scan = loadScanInScope(publicId, caller);

        InputStream inputStream = storageService.load(scan.getStorageKey());
        Resource resource = new InputStreamResource(inputStream);

        return new ScanFileData(
                resource,
                scan.getDetectedMimeType(),
                scan.getFileSizeBytes(),
                scan.getOriginalFilename()
        );
    }

    // ------------------------------------------------------------------
    // Access control & Scope checking
    // ------------------------------------------------------------------

    private Patient loadPatientInScope(String patientCode, User caller) {
        Patient patient = patientRepository.findByPatientCode(patientCode)
                .orElseThrow(() -> ResourceNotFoundException.patient(patientCode));

        if (!hasUnrestrictedScope(caller) && !isCreatedBy(patient, caller)) {
            log.warn("User {} attempted out-of-scope access to patient {}", caller.getUsername(), patientCode);
            throw ResourceNotFoundException.patient(patientCode);
        }
        return patient;
    }

    private Scan loadScanInScope(String publicId, User caller) {
        Scan scan = scanRepository.findWithPatientByPublicId(publicId)
                .orElseThrow(() -> ResourceNotFoundException.scan(publicId));

        if (!hasUnrestrictedScope(caller) && !isCreatedBy(scan.getPatient(), caller)) {
            log.warn("User {} attempted out-of-scope access to scan {}", caller.getUsername(), publicId);
            throw ResourceNotFoundException.scan(publicId);
        }
        return scan;
    }

    private boolean hasUnrestrictedScope(User caller) {
        return caller.getRole() == Role.ADMIN || caller.getRole() == Role.RESEARCHER;
    }

    private boolean isCreatedBy(Patient patient, User caller) {
        User creator = patient.getCreatedBy();
        return creator != null && creator.getId() != null
                && creator.getId().equals(caller.getId());
    }

    private void requireWriteAccess(User caller) {
        if (caller.getRole() == Role.RESEARCHER) {
            throw new ApiException(ApiErrorCode.FORBIDDEN,
                    "Role RESEARCHER may not upload or modify scans");
        }
    }

    private User requireCaller(JwtService.AuthenticatedPrincipal principal) {
        if (principal == null) {
            throw new ApiException(ApiErrorCode.UNAUTHORIZED, "No authenticated principal");
        }
        return userRepository.findByPublicId(principal.publicId())
                .filter(User::isEnabled)
                .orElseThrow(() -> new ApiException(ApiErrorCode.UNAUTHORIZED,
                        "Authenticated principal no longer resolves to an enabled user"));
    }
}
