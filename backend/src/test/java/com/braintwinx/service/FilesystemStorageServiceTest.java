package com.braintwinx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.braintwinx.config.StorageProperties;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("FilesystemStorageService")
class FilesystemStorageServiceTest {

    @TempDir
    Path tempStorageDir;

    private FilesystemStorageService storageService;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setRoot(tempStorageDir.toString());
        storageService = new FilesystemStorageService(properties);
    }

    @Nested
    @DisplayName("store and load")
    class StoreAndLoad {

        @Test
        void storesAndLoadsBytesExactly() throws IOException {
            byte[] content = "mri-scan-binary-data".getBytes(StandardCharsets.UTF_8);
            String key = storageService.generateScanStorageKey("png");

            String returnedKey = storageService.store(new ByteArrayInputStream(content), key);
            assertThat(returnedKey).isEqualTo(key);
            assertThat(storageService.exists(key)).isTrue();
            assertThat(storageService.size(key)).isEqualTo(content.length);

            try (var in = storageService.load(key)) {
                byte[] readBytes = in.readAllBytes();
                assertThat(readBytes).isEqualTo(content);
            }
        }

        @Test
        void deleteRemovesStoredFile() {
            byte[] content = "to-be-deleted".getBytes(StandardCharsets.UTF_8);
            String key = "scans/temp/delete_me.png";

            storageService.store(new ByteArrayInputStream(content), key);
            assertThat(storageService.exists(key)).isTrue();

            storageService.delete(key);
            assertThat(storageService.exists(key)).isFalse();
        }

        @Test
        void loadNonExistentKeyThrowsNotFound() {
            assertThatThrownBy(() -> storageService.load("scans/non_existent.png"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.SCAN_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("path traversal defence")
    class PathTraversal {

        @Test
        void relativeTraversalIsRejected() {
            assertThatThrownBy(() -> storageService.resolvePath("../../etc/passwd"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.INVALID_REQUEST));
        }

        @Test
        void windowsStyleTraversalIsRejected() {
            assertThatThrownBy(() -> storageService.resolvePath("..\\..\\Windows\\System32"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.INVALID_REQUEST));
        }

        @Test
        void blankKeyIsRejected() {
            assertThatThrownBy(() -> storageService.resolvePath("  "))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.INVALID_REQUEST));
        }
    }

    @Nested
    @DisplayName("storage key generation")
    class KeyGeneration {

        @Test
        void generatesStructuredKeyWithExtension() {
            String key = storageService.generateScanStorageKey("png");
            assertThat(key).startsWith("scans/");
            assertThat(key).endsWith(".png");
            assertThat(key.split("/")).hasSize(4); // scans / YYYY / MM / uuid.png
        }

        @Test
        void sanitizesLeadingDotInExtension() {
            String key = storageService.generateScanStorageKey(".jpg");
            assertThat(key).endsWith(".jpg");
            assertThat(key).doesNotContain("..jpg");
        }
    }
}
