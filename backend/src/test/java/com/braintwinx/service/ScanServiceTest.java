package com.braintwinx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.braintwinx.audit.AuditService;
import com.braintwinx.dto.ScanResponse;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.Patient;
import com.braintwinx.entity.Role;
import com.braintwinx.entity.Scan;
import com.braintwinx.entity.ScanStatus;
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
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScanService")
class ScanServiceTest {

    @Mock
    private ScanRepository scanRepository;

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private ImageValidationService imageValidationService;

    @Mock
    private AuditService auditService;

    private ScanMapper scanMapper = new ScanMapper();
    private ScanService scanService;

    private User doctorA;
    private User doctorB;
    private User researcher;
    private User admin;
    private Patient patientA;
    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setUp() throws Exception {
        scanService = new ScanService(
                scanRepository,
                patientRepository,
                userRepository,
                storageService,
                imageValidationService,
                scanMapper,
                auditService
        );

        doctorA = new User("doc.a", "doc.a@example.com", "hash", "Doctor A", Role.DOCTOR);
        setId(doctorA, 1L);

        doctorB = new User("doc.b", "doc.b@example.com", "hash", "Doctor B", Role.DOCTOR);
        setId(doctorB, 2L);

        researcher = new User("res.1", "res@example.com", "hash", "Researcher 1", Role.RESEARCHER);
        setId(researcher, 3L);

        admin = new User("admin.1", "admin@example.com", "hash", "Admin 1", Role.ADMIN);
        setId(admin, 4L);

        patientA = new Patient("PT-A", (short) 1980, com.braintwinx.entity.PatientSex.FEMALE, doctorA);
        setId(patientA, 100L);

        httpRequest = new MockHttpServletRequest();
    }

    private void setId(Object entity, Long id) throws Exception {
        Field idField = Class.forName("com.braintwinx.entity.BaseEntity").getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
    }

    private JwtService.AuthenticatedPrincipal principal(User user) {
        return new JwtService.AuthenticatedPrincipal(
                user.getPublicId(),
                user.getRole()
        );
    }

    @Nested
    @DisplayName("uploadScan")
    class UploadScan {

        @Test
        void doctorUploadingToOwnPatientSucceeds() {
            var princ = principal(doctorA);
            when(userRepository.findByPublicId(princ.publicId())).thenReturn(Optional.of(doctorA));
            when(patientRepository.findByPatientCode("PT-A")).thenReturn(Optional.of(patientA));

            MockMultipartFile file = new MockMultipartFile(
                    "file", "scan.png", "image/png", new byte[] {1, 2, 3});

            var validated = new ImageValidationService.ValidatedImage(
                    "image/png", "abc123sha", 256, 256, 3L, "scan.png", "png", new byte[] {1, 2, 3});
            when(imageValidationService.validate(any(), eq("scan.png"), eq("image/png")))
                    .thenReturn(validated);
            when(scanRepository.existsByPatientAndContentSha256(patientA, "abc123sha"))
                    .thenReturn(false);
            when(storageService.generateScanStorageKey("png"))
                    .thenReturn("scans/2026/08/scan-1.png");

            when(scanRepository.save(any(Scan.class))).thenAnswer(inv -> inv.getArgument(0));

            ScanResponse response = scanService.uploadScan(
                    "PT-A", LocalDate.now(), ScanType.MRI_T1, file, princ, httpRequest);

            assertThat(response).isNotNull();
            assertThat(response.patientCode()).isEqualTo("PT-A");
            assertThat(response.status()).isEqualTo(ScanStatus.UPLOADED);
            assertThat(response.detectedMimeType()).isEqualTo("image/png");

            verify(storageService).store(any(), eq("scans/2026/08/scan-1.png"));
            verify(auditService).record(eq(doctorA), eq("doc.a"), eq(AuditAction.SCAN_UPLOADED),
                    eq("SCAN"), anyString(), eq(true), any(), eq(httpRequest));
        }

        @Test
        void doctorUploadingToAnotherDoctorsPatientReturnsNotFound() {
            var princ = principal(doctorB);
            when(userRepository.findByPublicId(princ.publicId())).thenReturn(Optional.of(doctorB));
            when(patientRepository.findByPatientCode("PT-A")).thenReturn(Optional.of(patientA));

            MockMultipartFile file = new MockMultipartFile("file", "s.png", "image/png", new byte[] {1});

            assertThatThrownBy(() -> scanService.uploadScan(
                    "PT-A", LocalDate.now(), ScanType.MRI_T1, file, princ, httpRequest))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .satisfies(ex -> assertThat(((ResourceNotFoundException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.PATIENT_NOT_FOUND));

            verify(storageService, never()).store(any(), any());
        }

        @Test
        void researcherUploadingScanReturnsForbidden() {
            var princ = principal(researcher);
            when(userRepository.findByPublicId(princ.publicId())).thenReturn(Optional.of(researcher));

            MockMultipartFile file = new MockMultipartFile("file", "s.png", "image/png", new byte[] {1});

            assertThatThrownBy(() -> scanService.uploadScan(
                    "PT-A", LocalDate.now(), ScanType.MRI_T1, file, princ, httpRequest))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.FORBIDDEN));
        }

        @Test
        void uploadToArchivedPatientIsRejected() {
            patientA.archive(Instant.now());
            var princ = principal(doctorA);
            when(userRepository.findByPublicId(princ.publicId())).thenReturn(Optional.of(doctorA));
            when(patientRepository.findByPatientCode("PT-A")).thenReturn(Optional.of(patientA));

            MockMultipartFile file = new MockMultipartFile("file", "s.png", "image/png", new byte[] {1});

            assertThatThrownBy(() -> scanService.uploadScan(
                    "PT-A", LocalDate.now(), ScanType.MRI_T1, file, princ, httpRequest))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.INVALID_STATE_TRANSITION));
        }

        @Test
        void duplicateScanUploadThrowsConflict() {
            var princ = principal(doctorA);
            when(userRepository.findByPublicId(princ.publicId())).thenReturn(Optional.of(doctorA));
            when(patientRepository.findByPatientCode("PT-A")).thenReturn(Optional.of(patientA));

            MockMultipartFile file = new MockMultipartFile("file", "s.png", "image/png", new byte[] {1});
            var validated = new ImageValidationService.ValidatedImage(
                    "image/png", "dup-sha", 256, 256, 1L, "s.png", "png", new byte[] {1});
            when(imageValidationService.validate(any(), any(), any())).thenReturn(validated);
            when(scanRepository.existsByPatientAndContentSha256(patientA, "dup-sha")).thenReturn(true);

            assertThatThrownBy(() -> scanService.uploadScan(
                    "PT-A", LocalDate.now(), ScanType.MRI_T1, file, princ, httpRequest))
                    .isInstanceOf(DuplicateResourceException.class)
                    .satisfies(ex -> assertThat(((DuplicateResourceException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.DUPLICATE_RESOURCE));

            verify(auditService).record(eq(doctorA), eq("doc.a"), eq(AuditAction.SCAN_VALIDATION_FAILED),
                    eq("SCAN"), eq("PT-A"), eq(false), any(), eq(httpRequest));
            verify(storageService, never()).store(any(), any());
        }
    }

    @Nested
    @DisplayName("getScan and loadScanImage")
    class GetAndLoadScan {

        @Test
        void doctorCanReadOwnScan() {
            var princ = principal(doctorA);
            when(userRepository.findByPublicId(princ.publicId())).thenReturn(Optional.of(doctorA));

            Scan scan = new Scan(patientA, LocalDate.now(), ScanType.MRI_T1,
                    "key1", "image/png", 100L, "sha1", doctorA);
            when(scanRepository.findWithPatientByPublicId("scan-pub-1"))
                    .thenReturn(Optional.of(scan));

            ScanResponse response = scanService.getScan("scan-pub-1", princ);
            assertThat(response).isNotNull();
            assertThat(response.patientCode()).isEqualTo("PT-A");
        }

        @Test
        void doctorCannotReadAnotherDoctorsScan() {
            var princ = principal(doctorB);
            when(userRepository.findByPublicId(princ.publicId())).thenReturn(Optional.of(doctorB));

            Scan scan = new Scan(patientA, LocalDate.now(), ScanType.MRI_T1,
                    "key1", "image/png", 100L, "sha1", doctorA);
            when(scanRepository.findWithPatientByPublicId("scan-pub-1"))
                    .thenReturn(Optional.of(scan));

            assertThatThrownBy(() -> scanService.getScan("scan-pub-1", princ))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .satisfies(ex -> assertThat(((ResourceNotFoundException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.SCAN_NOT_FOUND));
        }

        @Test
        void loadScanImageReturnsResourceForAuthorizedCaller() {
            var princ = principal(doctorA);
            when(userRepository.findByPublicId(princ.publicId())).thenReturn(Optional.of(doctorA));

            Scan scan = new Scan(patientA, LocalDate.now(), ScanType.MRI_T1,
                    "key1", "image/png", 100L, "sha1", doctorA);
            scan.setOriginalFilename("test.png");
            when(scanRepository.findWithPatientByPublicId("scan-pub-1"))
                    .thenReturn(Optional.of(scan));
            when(storageService.load("key1")).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

            ScanService.ScanFileData fileData = scanService.loadScanImage("scan-pub-1", princ);
            assertThat(fileData).isNotNull();
            assertThat(fileData.mimeType()).isEqualTo("image/png");
            assertThat(fileData.originalFilename()).isEqualTo("test.png");
        }
    }
}
