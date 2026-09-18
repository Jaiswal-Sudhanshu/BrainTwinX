package com.braintwinx.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.braintwinx.config.StorageProperties;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ImageValidationService")
class ImageValidationServiceTest {

    private ImageValidationService validationService;
    private StorageProperties storageProperties;

    @BeforeEach
    void setUp() {
        storageProperties = new StorageProperties();
        storageProperties.setMaxUploadSizeBytes(1024 * 1024); // 1 MB
        storageProperties.setMaxDimension(4096);
        storageProperties.setMaxImagePixels(4000000);
        validationService = new ImageValidationService(storageProperties);
    }

    private byte[] createSampleImage(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        return baos.toByteArray();
    }

    @Nested
    @DisplayName("valid uploads")
    class ValidUploads {

        @Test
        void validPngSucceedsWithAccurateMetadata() throws IOException {
            byte[] pngBytes = createSampleImage("png", 64, 48);

            ImageValidationService.ValidatedImage result =
                    validationService.validate(pngBytes, "brain_scan_01.png", "image/png");

            assertThat(result.detectedMimeType()).isEqualTo("image/png");
            assertThat(result.imageWidth()).isEqualTo(64);
            assertThat(result.imageHeight()).isEqualTo(48);
            assertThat(result.fileSizeBytes()).isEqualTo(pngBytes.length);
            assertThat(result.extension()).isEqualTo("png");
            assertThat(result.sanitizedFilename()).isEqualTo("brain_scan_01.png");
            assertThat(result.contentSha256()).hasSize(64);
        }

        @Test
        void validJpegSucceeds() throws IOException {
            byte[] jpegBytes = createSampleImage("jpg", 100, 100);

            ImageValidationService.ValidatedImage result =
                    validationService.validate(jpegBytes, "scan.jpg", "image/jpeg");

            assertThat(result.detectedMimeType()).isEqualTo("image/jpeg");
            assertThat(result.imageWidth()).isEqualTo(100);
            assertThat(result.imageHeight()).isEqualTo(100);
            assertThat(result.extension()).isEqualTo("jpg");
        }
    }

    @Nested
    @DisplayName("hostile input and format defence")
    class HostileInput {

        @Test
        void emptyFileIsRejected() {
            assertThatThrownBy(() -> validationService.validate(new byte[0], "test.png", "image/png"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.INVALID_FILE));
        }

        @Test
        void oversizedFileIsRejected() {
            storageProperties.setMaxUploadSizeBytes(100);
            byte[] large = new byte[200];

            assertThatThrownBy(() -> validationService.validate(large, "large.png", "image/png"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.FILE_TOO_LARGE));
        }

        @Test
        void decompressionBombExceedingMaxDimensionIsRejected() throws IOException {
            storageProperties.setMaxDimension(30);
            byte[] pngBytes = createSampleImage("png", 32, 32);

            assertThatThrownBy(() -> validationService.validate(pngBytes, "bomb.png", "image/png"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.INVALID_FILE));
        }

        @Test
        void decompressionBombExceedingMaxPixelsIsRejected() throws IOException {
            storageProperties.setMaxImagePixels(500);
            byte[] pngBytes = createSampleImage("png", 32, 32); // 1024 pixels

            assertThatThrownBy(() -> validationService.validate(pngBytes, "bomb.png", "image/png"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.INVALID_FILE));
        }

        @ParameterizedTest
        @ValueSource(strings = {"scan.exe", "scan.pdf", "scan.txt", "scan.dicom", "scan.sh"})
        void unsupportedExtensionsRejected(String filename) {
            byte[] dummy = "dummy data".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> validationService.validate(dummy, filename, "application/octet-stream"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.UNSUPPORTED_MRI_FORMAT));
        }

        @Test
        void spoofedMimeTypeIsRejected() throws IOException {
            // PNG content declared as JPEG
            byte[] pngBytes = createSampleImage("png", 32, 32);

            assertThatThrownBy(() -> validationService.validate(pngBytes, "scan.png", "image/jpeg"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.INVALID_FILE));
        }

        @Test
        void extensionMismatchAgainstMagicBytesIsRejected() throws IOException {
            // JPEG bytes named with a .png extension
            byte[] jpegBytes = createSampleImage("jpg", 32, 32);

            assertThatThrownBy(() -> validationService.validate(jpegBytes, "fake_png.png", "image/png"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.INVALID_FILE));
        }

        @Test
        void corruptImagePayloadIsRejected() {
            // Begins with PNG signature but has corrupt tail
            byte[] fakePng = new byte[] {
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                    0x00, 0x00, 0x00, 0x00, 0x12, 0x34
            };

            assertThatThrownBy(() -> validationService.validate(fakePng, "corrupt.png", "image/png"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.INVALID_FILE));
        }

        @Test
        void nonImageMagicBytesRejectedAsUnsupported() {
            byte[] textFile = "Plain text document pretending to be an image".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> validationService.validate(textFile, "fake.png", "image/png"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.UNSUPPORTED_MRI_FORMAT));
        }
    }

    @Nested
    @DisplayName("filename sanitization")
    class FilenameSanitization {

        @Test
        void pathTraversalSequencesStripped() {
            String sanitized = validationService.sanitizeFilename("../../etc/passwd.png");
            assertThat(sanitized).doesNotContain("..");
            assertThat(sanitized).doesNotContain("/");
            assertThat(sanitized).endsWith(".png");
        }

        @Test
        void windowsReservedNamesDisarmed() {
            String sanitized = validationService.sanitizeFilename("CON.png");
            assertThat(sanitized).isEqualTo("safe_CON.png");
        }

        @Test
        void controlAndNulCharactersRemoved() {
            String sanitized = validationService.sanitizeFilename("scan\0name\u0007.jpg");
            assertThat(sanitized).doesNotContain("\0");
            assertThat(sanitized).doesNotContain("\u0007");
            assertThat(sanitized).endsWith(".jpg");
        }
    }
}
