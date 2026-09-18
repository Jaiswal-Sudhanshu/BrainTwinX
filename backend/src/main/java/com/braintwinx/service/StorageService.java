package com.braintwinx.service;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Port for persisting and retrieving scan artifacts and reports.
 *
 * <p>Isolates the domain and controllers from physical storage details (filesystem,
 * object store, etc.), per ADR-002 and project brief section 9.
 */
public interface StorageService {

    /**
     * Stores content from the given input stream under {@code storageKey}.
     *
     * @param inputStream the data stream to store
     * @param storageKey the relative server-generated storage key
     * @return the resolved storage key
     */
    String store(InputStream inputStream, String storageKey);

    /**
     * Opens an input stream for reading the content identified by {@code storageKey}.
     *
     * @param storageKey the relative storage key
     * @return an open {@link InputStream}
     */
    InputStream load(String storageKey);

    /**
     * Checks if content exists under {@code storageKey}.
     */
    boolean exists(String storageKey);

    /**
     * Deletes content under {@code storageKey}, if present.
     */
    void delete(String storageKey);

    /**
     * Returns the size of the stored object in bytes.
     */
    long size(String storageKey);

    /**
     * Resolves the storage key to a local filesystem path if backed by a filesystem.
     */
    Path resolvePath(String storageKey);

    /**
     * Generates a unique, server-controlled storage key for a scan file.
     *
     * <p>Client input is never used to construct this path (brief section 9).
     *
     * @param extension the verified file extension (e.g. "png", "jpg")
     * @return a relative storage key like {@code scans/2026/08/uuid.png}
     */
    String generateScanStorageKey(String extension);
}
