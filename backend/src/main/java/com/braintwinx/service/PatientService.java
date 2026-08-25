package com.braintwinx.service;

import com.braintwinx.audit.AuditService;
import com.braintwinx.dto.PageResponse;
import com.braintwinx.dto.PatientCreateRequest;
import com.braintwinx.dto.PatientResponse;
import com.braintwinx.dto.PatientUpdateRequest;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.PatientSex;
import com.braintwinx.entity.PatientStatus;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.User;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import com.braintwinx.exception.DuplicateResourceException;
import com.braintwinx.exception.ResourceNotFoundException;
import com.braintwinx.mapper.PatientMapper;
import com.braintwinx.repository.PatientRepository;
import com.braintwinx.repository.UserRepository;
import com.braintwinx.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patient record management (project brief section 8).
 *
 * <p><strong>Access scope (ASSUMPTIONS.md A-16).</strong> Enforced here, in the service, rather
 * than in the controller — so it applies however the operation is reached:
 *
 * <ul>
 *   <li>{@link Role#ADMIN} — unrestricted.</li>
 *   <li>{@link Role#DOCTOR} — restricted to patients they created (their own caseload).</li>
 *   <li>{@link Role#RESEARCHER} — read-only across all patients. Records are already
 *       data-minimised (A-8), and research work is inherently cross-cohort.</li>
 * </ul>
 *
 * <p><strong>Out-of-scope access returns 404, never 403.</strong> A 403 would confirm that a
 * patient with that code exists, letting a caller enumerate other clinicians' caseloads one code at
 * a time. Returning "not found" for both absent and forbidden keeps the two indistinguishable —
 * the IDOR defence in brief section 54.
 *
 * <p><strong>PHI discipline.</strong> No log statement or exception message in this class includes
 * {@code birthYear} or {@code sex}. {@code patientCode} is safe to log by construction: it is the
 * public-safe identifier the API is built around (A-8).
 */
@Service
public class PatientService {

    private static final Logger log = LoggerFactory.getLogger(PatientService.class);

    private static final String RESOURCE_TYPE = "PATIENT";

    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final PatientMapper patientMapper;
    private final AuditService auditService;

    public PatientService(PatientRepository patientRepository,
                          UserRepository userRepository,
                          PatientMapper patientMapper,
                          AuditService auditService) {
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.patientMapper = patientMapper;
        this.auditService = auditService;
    }

    /**
     * Creates a patient record.
     *
     * @throws DuplicateResourceException if the code is already taken
     * @throws ApiException              {@code FORBIDDEN} if the caller may not create records
     */
    @Transactional
    public PatientResponse create(PatientCreateRequest request,
                                  JwtService.AuthenticatedPrincipal principal,
                                  HttpServletRequest httpRequest) {
        User caller = requireCaller(principal);
        requireWriteAccess(caller);

        // Checked explicitly so the caller gets a clear 409 rather than a generic integrity
        // violation from the unique constraint. The constraint remains the real guarantee:
        // this check is not race-free, and the database is what makes it correct.
        if (patientRepository.existsByPatientCode(request.patientCode())) {
            auditService.record(caller, caller.getUsername(), AuditAction.PATIENT_CREATED,
                    RESOURCE_TYPE, request.patientCode(), false,
                    java.util.Map.of("reason", "DUPLICATE_PATIENT_CODE"), httpRequest);
            throw new DuplicateResourceException(
                    "Patient code already exists: " + request.patientCode());
        }

        Patient patient = new Patient(
                request.patientCode(),
                request.birthYear(),
                request.sex() != null ? request.sex() : PatientSex.UNKNOWN,
                caller);

        Patient saved = patientRepository.save(patient);
        log.info("Patient {} created", saved.getPatientCode());

        auditService.record(caller, caller.getUsername(), AuditAction.PATIENT_CREATED,
                RESOURCE_TYPE, saved.getPatientCode(), true, null, httpRequest);

        return patientMapper.toResponse(saved);
    }

    /**
     * Reads one patient.
     *
     * @throws ResourceNotFoundException if absent <em>or</em> out of the caller's scope
     */
    @Transactional(readOnly = true)
    public PatientResponse get(String patientCode,
                               JwtService.AuthenticatedPrincipal principal,
                               HttpServletRequest httpRequest) {
        User caller = requireCaller(principal);
        Patient patient = loadInScope(patientCode, caller);

        auditService.record(caller, caller.getUsername(), AuditAction.PATIENT_VIEWED,
                RESOURCE_TYPE, patient.getPatientCode(), true, null, httpRequest);

        return patientMapper.toResponse(patient);
    }

    /**
     * Lists patients visible to the caller, filtered by status.
     *
     * <p>A DOCTOR's listing is scoped in the query, not filtered afterwards — see
     * {@link PatientRepository#findByCreatedByAndStatus}.
     */
    @Transactional(readOnly = true)
    public PageResponse<PatientResponse> list(PatientStatus status,
                                              Pageable pageable,
                                              JwtService.AuthenticatedPrincipal principal) {
        User caller = requireCaller(principal);
        PatientStatus effectiveStatus = status != null ? status : PatientStatus.ACTIVE;

        Page<Patient> page = hasUnrestrictedScope(caller)
                ? patientRepository.findByStatus(effectiveStatus, pageable)
                : patientRepository.findByCreatedByAndStatus(caller, effectiveStatus, pageable);

        return PageResponse.from(page.map(patientMapper::toResponse));
    }

    /**
     * Updates the mutable fields of a patient record.
     *
     * <p>{@code patientCode} and {@code status} are not updatable here: the code is the correlation
     * key for the patient's entire history, and archiving is a separate audited operation.
     *
     * @throws ResourceNotFoundException if absent or out of scope
     * @throws ApiException              {@code FORBIDDEN} if the caller may not write;
     *                                   {@code INVALID_STATE_TRANSITION} if the record is archived
     */
    @Transactional
    public PatientResponse update(String patientCode,
                                  PatientUpdateRequest request,
                                  JwtService.AuthenticatedPrincipal principal,
                                  HttpServletRequest httpRequest) {
        User caller = requireCaller(principal);
        requireWriteAccess(caller);
        Patient patient = loadInScope(patientCode, caller);

        if (!patient.isActive()) {
            // An archived record is a historical artefact. Editing it would rewrite history that
            // existing scans, reports, and audit entries already refer to.
            throw new ApiException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot update an archived patient: " + patientCode);
        }

        // Absent fields are left unchanged rather than nulled: this is a partial update, and
        // silently erasing a value the caller did not mention would be data loss.
        if (request.birthYear() != null) {
            patient.setBirthYear(request.birthYear());
        }
        if (request.sex() != null) {
            patient.setSex(request.sex());
        }

        Patient saved = patientRepository.save(patient);
        log.info("Patient {} updated", saved.getPatientCode());

        auditService.record(caller, caller.getUsername(), AuditAction.PATIENT_UPDATED,
                RESOURCE_TYPE, saved.getPatientCode(), true, null, httpRequest);

        return patientMapper.toResponse(saved);
    }

    /**
     * Archives a patient record.
     *
     * <p>A soft operation, never a delete (ASSUMPTIONS.md A-13). Scans, predictions, reports, and
     * audit entries reference the patient, and brief section 27 requires the audit trail be
     * preserved — a hard delete would orphan it.
     *
     * <p>Idempotent: archiving an already-archived record succeeds without a second audit entry, so
     * a retried request does not inflate the trail.
     *
     * @throws ResourceNotFoundException if absent or out of scope
     * @throws ApiException              {@code FORBIDDEN} if the caller may not write
     */
    @Transactional
    public void archive(String patientCode,
                        JwtService.AuthenticatedPrincipal principal,
                        HttpServletRequest httpRequest) {
        User caller = requireCaller(principal);
        requireWriteAccess(caller);
        Patient patient = loadInScope(patientCode, caller);

        if (!patient.isActive()) {
            log.debug("Patient {} is already archived; archive request is a no-op", patientCode);
            return;
        }

        patient.archive(Instant.now());
        patientRepository.save(patient);
        log.info("Patient {} archived", patientCode);

        auditService.record(caller, caller.getUsername(), AuditAction.PATIENT_ARCHIVED,
                RESOURCE_TYPE, patientCode, true, null, httpRequest);
    }

    // ------------------------------------------------------------------
    // Access control
    // ------------------------------------------------------------------

    /**
     * Loads a patient the caller is permitted to see.
     *
     * <p>Both "no such patient" and "not yours" raise the same {@link ResourceNotFoundException},
     * so a caller cannot distinguish them and therefore cannot probe for the existence of records
     * outside their scope.
     */
    private Patient loadInScope(String patientCode, User caller) {
        Patient patient = patientRepository.findByPatientCode(patientCode)
                .orElseThrow(() -> ResourceNotFoundException.patient(patientCode));

        if (!hasUnrestrictedScope(caller) && !isCreatedBy(patient, caller)) {
            // Logged so a genuine authorisation problem is diagnosable, while the response stays
            // indistinguishable from a plain 404.
            log.warn("User {} attempted out-of-scope access to patient {}", caller.getUsername(),
                    patientCode);
            throw ResourceNotFoundException.patient(patientCode);
        }
        return patient;
    }

    /** ADMIN sees everything; RESEARCHER reads everything (records are already minimised). */
    private boolean hasUnrestrictedScope(User caller) {
        return caller.getRole() == Role.ADMIN || caller.getRole() == Role.RESEARCHER;
    }

    private boolean isCreatedBy(Patient patient, User caller) {
        User creator = patient.getCreatedBy();
        return creator != null && creator.getId() != null
                && creator.getId().equals(caller.getId());
    }

    /**
     * @throws ApiException {@code FORBIDDEN} if the caller's role is read-only
     */
    private void requireWriteAccess(User caller) {
        if (caller.getRole() == Role.RESEARCHER) {
            throw new ApiException(ApiErrorCode.FORBIDDEN,
                    "Role RESEARCHER may not modify patient records");
        }
    }

    /**
     * Resolves the authenticated principal to a persisted user.
     *
     * <p>A token can outlive the account it names — it is stateless and revocation only ends
     * refresh. Re-resolving on every call means a deleted or disabled user cannot keep acting on a
     * still-valid access token.
     *
     * @throws ApiException {@code UNAUTHORIZED} if there is no usable principal
     */
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
