package com.braintwinx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.braintwinx.audit.AuditService;
import com.braintwinx.dto.PatientCreateRequest;
import com.braintwinx.dto.PatientUpdateRequest;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.PatientSex;
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
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PatientService}, focused on access scope and state rules.
 *
 * <p>Deliberately fast and Docker-free: these cover the decision logic, while
 * {@code PatientManagementIT} covers the wired stack against real MySQL. Keeping the scope rules
 * under unit test as well means a regression is caught in milliseconds rather than only by a
 * container-backed suite.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PatientService")
class PatientServiceTest {

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditService auditService;

    private PatientService patientService;

    private User admin;
    private User doctorA;
    private User doctorB;
    private User researcher;

    @BeforeEach
    void setUp() {
        patientService = new PatientService(patientRepository, userRepository,
                new PatientMapper(), auditService);

        admin = userWithId(1L, "admin.user", Role.ADMIN);
        doctorA = userWithId(2L, "doctor.a", Role.DOCTOR);
        doctorB = userWithId(3L, "doctor.b", Role.DOCTOR);
        researcher = userWithId(4L, "researcher.user", Role.RESEARCHER);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("access scope")
    class AccessScope {

        @Test
        @DisplayName("a doctor may read their own patient")
        void doctorReadsOwnPatient() {
            Patient patient = patientOf(doctorA, "BTX-OWN");
            stubCaller(doctorA);
            when(patientRepository.findByPatientCode("BTX-OWN")).thenReturn(Optional.of(patient));

            var response = patientService.get("BTX-OWN", principalOf(doctorA), null);

            assertThat(response.patientCode()).isEqualTo("BTX-OWN");
        }

        @Test
        @DisplayName("a doctor reading another doctor's patient gets not-found, not forbidden")
        void doctorCannotReadOthersPatient() {
            // The distinction matters: a forbidden response would confirm the record exists.
            Patient patient = patientOf(doctorA, "BTX-OTHER");
            stubCaller(doctorB);
            when(patientRepository.findByPatientCode("BTX-OTHER")).thenReturn(Optional.of(patient));

            assertThatThrownBy(() -> patientService.get("BTX-OTHER", principalOf(doctorB), null))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .extracting(ex -> ((ApiException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.PATIENT_NOT_FOUND);
        }

        @Test
        @DisplayName("an admin may read any patient")
        void adminReadsAnyPatient() {
            Patient patient = patientOf(doctorA, "BTX-ANY");
            stubCaller(admin);
            when(patientRepository.findByPatientCode("BTX-ANY")).thenReturn(Optional.of(patient));

            assertThat(patientService.get("BTX-ANY", principalOf(admin), null).patientCode())
                    .isEqualTo("BTX-ANY");
        }

        @Test
        @DisplayName("a researcher may read any patient")
        void researcherReadsAnyPatient() {
            Patient patient = patientOf(doctorA, "BTX-RES");
            stubCaller(researcher);
            when(patientRepository.findByPatientCode("BTX-RES")).thenReturn(Optional.of(patient));

            assertThat(patientService.get("BTX-RES", principalOf(researcher), null).patientCode())
                    .isEqualTo("BTX-RES");
        }

        @Test
        @DisplayName("a researcher may not create")
        void researcherCannotCreate() {
            stubCaller(researcher);

            assertThatThrownBy(() -> patientService.create(
                    new PatientCreateRequest("BTX-NEW", null, null), principalOf(researcher), null))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.FORBIDDEN);

            verify(patientRepository, never()).save(any());
        }

        @Test
        @DisplayName("a researcher may not archive")
        void researcherCannotArchive() {
            stubCaller(researcher);

            assertThatThrownBy(() ->
                    patientService.archive("BTX-ANY", principalOf(researcher), null))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("a principal that no longer resolves to an enabled user is rejected")
        void disabledPrincipalRejected() {
            // An access token is stateless and outlives revocation, so the user is re-resolved
            // on every call rather than trusted from the token alone.
            doctorA.setEnabled(false);
            when(userRepository.findByPublicId(doctorA.getPublicId()))
                    .thenReturn(Optional.of(doctorA));

            assertThatThrownBy(() -> patientService.get("BTX-ANY", principalOf(doctorA), null))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("a null principal is rejected")
        void nullPrincipalRejected() {
            assertThatThrownBy(() -> patientService.get("BTX-ANY", null, null))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.UNAUTHORIZED);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("a duplicate code is rejected and nothing is saved")
        void duplicateRejected() {
            stubCaller(doctorA);
            when(patientRepository.existsByPatientCode("BTX-DUP")).thenReturn(true);

            assertThatThrownBy(() -> patientService.create(
                    new PatientCreateRequest("BTX-DUP", null, null), principalOf(doctorA), null))
                    .isInstanceOf(DuplicateResourceException.class);

            verify(patientRepository, never()).save(any());
        }

        @Test
        @DisplayName("a duplicate attempt is audited as unsuccessful")
        void duplicateIsAudited() {
            stubCaller(doctorA);
            when(patientRepository.existsByPatientCode("BTX-DUP")).thenReturn(true);

            assertThatThrownBy(() -> patientService.create(
                    new PatientCreateRequest("BTX-DUP", null, null), principalOf(doctorA), null))
                    .isInstanceOf(DuplicateResourceException.class);

            verify(auditService).record(eq(doctorA), eq("doctor.a"),
                    eq(AuditAction.PATIENT_CREATED), eq("PATIENT"), eq("BTX-DUP"),
                    eq(false), any(), any());
        }

        @Test
        @DisplayName("an absent sex is stored as UNKNOWN rather than inferred")
        void absentSexBecomesUnknown() {
            stubCaller(doctorA);
            when(patientRepository.existsByPatientCode("BTX-NEW")).thenReturn(false);
            when(patientRepository.save(any(Patient.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var response = patientService.create(
                    new PatientCreateRequest("BTX-NEW", (short) 1990, null),
                    principalOf(doctorA), null);

            assertThat(response.sex()).isEqualTo(PatientSex.UNKNOWN);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("update and archive")
    class UpdateAndArchive {

        @Test
        @DisplayName("an omitted field is left unchanged rather than nulled")
        void partialUpdatePreservesOmittedFields() {
            Patient patient = patientOf(doctorA, "BTX-UPD");
            patient.setBirthYear((short) 1980);
            patient.setSex(PatientSex.FEMALE);
            stubCaller(doctorA);
            when(patientRepository.findByPatientCode("BTX-UPD")).thenReturn(Optional.of(patient));
            when(patientRepository.save(any(Patient.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var response = patientService.update("BTX-UPD",
                    new PatientUpdateRequest((short) 1981, null), principalOf(doctorA), null);

            assertThat(response.birthYear()).isEqualTo((short) 1981);
            assertThat(response.sex())
                    .as("Silently erasing an unmentioned value would be data loss")
                    .isEqualTo(PatientSex.FEMALE);
        }

        @Test
        @DisplayName("an archived patient cannot be updated")
        void archivedCannotBeUpdated() {
            Patient patient = patientOf(doctorA, "BTX-ARCH");
            patient.archive(Instant.now());
            stubCaller(doctorA);
            when(patientRepository.findByPatientCode("BTX-ARCH")).thenReturn(Optional.of(patient));

            assertThatThrownBy(() -> patientService.update("BTX-ARCH",
                    new PatientUpdateRequest((short) 1990, null), principalOf(doctorA), null))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.INVALID_STATE_TRANSITION);

            verify(patientRepository, never()).save(any());
        }

        @Test
        @DisplayName("archiving an already-archived patient is a no-op and is not re-audited")
        void archiveIsIdempotent() {
            Patient patient = patientOf(doctorA, "BTX-IDEM");
            patient.archive(Instant.now());
            stubCaller(doctorA);
            when(patientRepository.findByPatientCode("BTX-IDEM")).thenReturn(Optional.of(patient));

            patientService.archive("BTX-IDEM", principalOf(doctorA), null);

            verify(patientRepository, never()).save(any());
            // A retried request must not inflate the audit trail.
            verify(auditService, never()).record(any(), any(), eq(AuditAction.PATIENT_ARCHIVED),
                    any(), any(), anyBoolean(), any(), any());
        }

        @Test
        @DisplayName("archiving sets the status and timestamp together")
        void archiveSetsStatusAndTimestamp() {
            Patient patient = patientOf(doctorA, "BTX-ARCHIVE");
            stubCaller(doctorA);
            when(patientRepository.findByPatientCode("BTX-ARCHIVE"))
                    .thenReturn(Optional.of(patient));
            when(patientRepository.save(any(Patient.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            patientService.archive("BTX-ARCHIVE", principalOf(doctorA), null);

            // Both must move together, or the database CHECK constraint would reject the write.
            assertThat(patient.isActive()).isFalse();
            assertThat(patient.getArchivedAt()).isNotNull();
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private void stubCaller(User caller) {
        when(userRepository.findByPublicId(caller.getPublicId())).thenReturn(Optional.of(caller));
    }

    private JwtService.AuthenticatedPrincipal principalOf(User user) {
        return new JwtService.AuthenticatedPrincipal(user.getPublicId(), user.getRole());
    }

    private Patient patientOf(User creator, String code) {
        return new Patient(code, null, PatientSex.UNKNOWN, creator);
    }

    /**
     * Assigns an id via reflection.
     *
     * <p>Necessary because the scope check compares persisted ids and JPA normally assigns them.
     * Reflection is confined to this fixture rather than exposed as a production setter, so
     * production code cannot assign a key by accident.
     */
    private User userWithId(Long id, String username, Role role) {
        User user = new User(username, username + "@example.invalid", "hash",
                "Test " + username, role);
        try {
            Field idField = Class.forName("com.braintwinx.entity.BaseEntity")
                    .getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not assign test id", ex);
        }
        return user;
    }
}
