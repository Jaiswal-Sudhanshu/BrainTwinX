package com.braintwinx.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for storage and scan file validation.
 */
@Validated
@ConfigurationProperties(prefix = "braintwinx.storage")
public class StorageProperties {

    @NotBlank
    private String root = "./storage";

    @Min(1)
    private long maxUploadSizeBytes = 52_428_800L; // 50 MB

    @Min(1)
    private long maxImagePixels = 40_000_000L;

    @Min(1)
    private int maxDimension = 8192;

    private Set<String> allowedExtensions = Set.of("png", "jpg", "jpeg");

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public Path getRootPath() {
        return Paths.get(root).toAbsolutePath().normalize();
    }

    public long getMaxUploadSizeBytes() {
        return maxUploadSizeBytes;
    }

    public void setMaxUploadSizeBytes(long maxUploadSizeBytes) {
        this.maxUploadSizeBytes = maxUploadSizeBytes;
    }

    public long getMaxImagePixels() {
        return maxImagePixels;
    }

    public void setMaxImagePixels(long maxImagePixels) {
        this.maxImagePixels = maxImagePixels;
    }

    public int getMaxDimension() {
        return maxDimension;
    }

    public void setMaxDimension(int maxDimension) {
        this.maxDimension = maxDimension;
    }

    public Set<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(Set<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }
}
