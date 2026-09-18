package com.braintwinx.service;

import com.braintwinx.config.StorageProperties;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Filesystem implementation of {@link StorageService}.
 *
 * <p><strong>Path traversal defence:</strong> All paths are normalized and verified to remain
 * within the configured storage root directory. No client-supplied path element is ever accepted.
 */
@Service
public class FilesystemStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(FilesystemStorageService.class);
    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM");

    private final Path rootPath;

    public FilesystemStorageService(StorageProperties storageProperties) {
        this.rootPath = storageProperties.getRootPath();
        try {
            Files.createDirectories(this.rootPath);
        } catch (IOException e) {
            throw new ApiException(ApiErrorCode.STORAGE_FAILURE,
                    "Failed to initialize storage directory: " + rootPath, e);
        }
    }

    @Override
    public String store(InputStream inputStream, String storageKey) {
        Path destination = resolvePath(storageKey);
        try {
            Files.createDirectories(destination.getParent());
            Path tempFile = Files.createTempFile(destination.getParent(), "upload-", ".tmp");
            try {
                try (var in = new BufferedInputStream(inputStream);
                     var out = new BufferedOutputStream(Files.newOutputStream(tempFile))) {
                    in.transferTo(out);
                }
                Files.move(tempFile, destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                log.debug("Stored file under key: {}", storageKey);
                return storageKey;
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            log.error("Failed to store file under key {}", storageKey, e);
            throw new ApiException(ApiErrorCode.STORAGE_FAILURE,
                    "I/O error storing file under " + storageKey, e);
        }
    }

    @Override
    public InputStream load(String storageKey) {
        Path path = resolvePath(storageKey);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new ApiException(ApiErrorCode.SCAN_NOT_FOUND,
                    "Storage key does not exist or is not a regular file: " + storageKey);
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            log.error("Failed to read storage key: {}", storageKey, e);
            throw new ApiException(ApiErrorCode.STORAGE_FAILURE,
                    "Failed to read file from storage", e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            Path path = resolvePath(storageKey);
            return Files.exists(path) && Files.isRegularFile(path);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Path path = resolvePath(storageKey);
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete file for storage key: {}", storageKey, e);
        }
    }

    @Override
    public long size(String storageKey) {
        Path path = resolvePath(storageKey);
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new ApiException(ApiErrorCode.STORAGE_FAILURE,
                    "Failed to determine size of file: " + storageKey, e);
        }
    }

    @Override
    public Path resolvePath(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST, "Storage key cannot be blank");
        }
        // Normalize and defend against path traversal
        Path resolved = rootPath.resolve(storageKey).normalize();
        if (!resolved.startsWith(rootPath)) {
            log.error("Path traversal attempt detected with storageKey: {}", storageKey);
            throw new ApiException(ApiErrorCode.INVALID_REQUEST, "Invalid storage key path traversal");
        }
        return resolved;
    }

    @Override
    public String generateScanStorageKey(String extension) {
        String cleanExt = (extension == null || extension.isBlank()) ? "png" : extension.toLowerCase().replace(".", "");
        String dateSubdir = LocalDate.now().format(DATE_PATH_FORMAT);
        String filename = UUID.randomUUID() + "." + cleanExt;
        return "scans/" + dateSubdir + "/" + filename;
    }
}
