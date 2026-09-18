package com.braintwinx.validation;

import com.braintwinx.config.StorageProperties;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validates uploaded MRI scan files at the untrusted network boundary.
 *
 * <p>Enforces:
 * <ul>
 *   <li>File size caps.</li>
 *   <li>Extension allow-list (PNG, JPEG per ASSUMPTIONS.md A-6).</li>
 *   <li>Magic-byte signature verification (never trusts declared MIME).</li>
 *   <li>Declared MIME vs detected signature agreement.</li>
 *   <li>Decompression-bomb bounds (pixel count and dimension caps).</li>
 *   <li>Corruption and readability checks.</li>
 *   <li>Filename sanitization (path traversal, NUL bytes, reserved names).</li>
 *   <li>SHA-256 digest computation.</li>
 * </ul>
 */
@Service
public class ImageValidationService {

    private static final Logger log = LoggerFactory.getLogger(ImageValidationService.class);

    // PNG magic bytes: 0x89 50 4E 47 0D 0A 1A 0A
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    // JPEG magic bytes: 0xFF 0xD8 0xFF
    private static final byte[] JPEG_SIGNATURE = new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF
    };

    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static final Pattern SAFE_FILENAME_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

    private final StorageProperties storageProperties;

    public ImageValidationService(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    public record ValidatedImage(
            String detectedMimeType,
            String contentSha256,
            int imageWidth,
            int imageHeight,
            long fileSizeBytes,
            String sanitizedFilename,
            String extension,
            byte[] bytes
    ) {}

    /**
     * Validates raw bytes and file metadata.
     *
     * @param rawBytes the binary payload
     * @param originalFilename the filename declared by client
     * @param declaredContentType the content-type declared by client
     * @return {@link ValidatedImage} with validated dimensions, hash, and MIME type
     */
    public ValidatedImage validate(byte[] rawBytes, String originalFilename, String declaredContentType) {
        if (rawBytes == null || rawBytes.length == 0) {
            throw new ApiException(ApiErrorCode.INVALID_FILE, "Uploaded file is empty");
        }

        if (rawBytes.length > storageProperties.getMaxUploadSizeBytes()) {
            throw new ApiException(ApiErrorCode.FILE_TOO_LARGE,
                    "File size %d exceeds max allowed %d bytes".formatted(
                            rawBytes.length, storageProperties.getMaxUploadSizeBytes()));
        }

        // 1. Sanitize original filename and extract extension
        String sanitized = sanitizeFilename(originalFilename);
        String extension = extractExtension(sanitized);

        if (!storageProperties.getAllowedExtensions().contains(extension)) {
            throw new ApiException(ApiErrorCode.UNSUPPORTED_MRI_FORMAT,
                    "Extension '%s' is not supported. Allowed: %s".formatted(
                            extension, storageProperties.getAllowedExtensions()));
        }

        // 2. Magic-byte detection
        String detectedMime = detectMimeType(rawBytes);
        if (detectedMime == null) {
            throw new ApiException(ApiErrorCode.UNSUPPORTED_MRI_FORMAT,
                    "File content magic bytes do not match any supported MRI image format");
        }

        // 3. Extension vs detected MIME check
        verifyExtensionMatchesMime(extension, detectedMime);

        // 4. Declared MIME vs detected MIME check (spoofing defence)
        verifyDeclaredMimeMatchesDetected(declaredContentType, detectedMime);

        // 5. Decode dimensions & check integrity (decompression bomb protection)
        ImageDimensions dims = decodeAndValidateDimensions(rawBytes);

        // 6. SHA-256 calculation
        String sha256 = calculateSha256(rawBytes);

        return new ValidatedImage(
                detectedMime,
                sha256,
                dims.width(),
                dims.height(),
                rawBytes.length,
                sanitized,
                extension,
                rawBytes
        );
    }

    private String detectMimeType(byte[] bytes) {
        if (hasSignature(bytes, PNG_SIGNATURE)) {
            return "image/png";
        }
        if (hasSignature(bytes, JPEG_SIGNATURE)) {
            return "image/jpeg";
        }
        return null;
    }

    private boolean hasSignature(byte[] data, byte[] signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private void verifyExtensionMatchesMime(String extension, String detectedMime) {
        if ("png".equals(extension) && !"image/png".equals(detectedMime)) {
            throw new ApiException(ApiErrorCode.INVALID_FILE,
                    "File extension .png does not match detected format " + detectedMime);
        }
        if (("jpg".equals(extension) || "jpeg".equals(extension)) && !"image/jpeg".equals(detectedMime)) {
            throw new ApiException(ApiErrorCode.INVALID_FILE,
                    "File extension ." + extension + " does not match detected format " + detectedMime);
        }
    }

    private void verifyDeclaredMimeMatchesDetected(String declaredContentType, String detectedMime) {
        if (declaredContentType != null && !declaredContentType.isBlank()) {
            String declared = declaredContentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
            // If the client declared a non-image or conflicting type, reject
            if (!declared.equals(detectedMime) && !("image/jpg".equals(declared) && "image/jpeg".equals(detectedMime))) {
                log.warn("MIME spoofing detected: declared '{}' vs detected '{}'", declared, detectedMime);
                throw new ApiException(ApiErrorCode.INVALID_FILE,
                        "Declared content-type '%s' does not match verified signature '%s'"
                                .formatted(declared, detectedMime));
            }
        }
    }

    private record ImageDimensions(int width, int height) {}

    private ImageDimensions decodeAndValidateDimensions(byte[] bytes) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                throw new ApiException(ApiErrorCode.INVALID_FILE, "Cannot initialize image input stream");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new ApiException(ApiErrorCode.INVALID_FILE, "No image reader available for file content");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if (width <= 0 || height <= 0) {
                    throw new ApiException(ApiErrorCode.INVALID_FILE, "Image dimensions must be positive");
                }

                if (width > storageProperties.getMaxDimension() || height > storageProperties.getMaxDimension()) {
                    throw new ApiException(ApiErrorCode.INVALID_FILE,
                            "Image dimension (%dx%d) exceeds maximum allowed dimension %d"
                                    .formatted(width, height, storageProperties.getMaxDimension()));
                }

                long pixelCount = (long) width * height;
                if (pixelCount > storageProperties.getMaxImagePixels()) {
                    throw new ApiException(ApiErrorCode.INVALID_FILE,
                            "Image pixel count %d exceeds safety limit %d"
                                    .formatted(pixelCount, storageProperties.getMaxImagePixels()));
                }

                // Full decode check to ensure the payload is not truncated or corrupted
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image == null) {
                    throw new ApiException(ApiErrorCode.INVALID_FILE, "Image stream could not be decoded");
                }

                return new ImageDimensions(width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            log.warn("Corrupt or malformed image rejected: {}", e.getMessage());
            throw new ApiException(ApiErrorCode.INVALID_FILE, "Image is corrupt or cannot be decoded", e);
        }
    }

    public String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "scan_upload";
        }

        // Remove NUL bytes and control characters first to avoid OS path parser exceptions
        String clean = filename.replace("\0", "").replaceAll("[\\p{Cntrl}]", "");

        // Strip directory traversal components (/ and \) safely
        int lastSlash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        String basename = lastSlash >= 0 ? clean.substring(lastSlash + 1) : clean;

        // Extract stem and extension
        int dot = basename.lastIndexOf('.');
        String stem = dot >= 0 ? basename.substring(0, dot) : basename;
        String ext = dot >= 0 ? basename.substring(dot) : "";

        // Check Windows reserved names
        if (WINDOWS_RESERVED_NAMES.contains(stem.toUpperCase(Locale.ROOT))) {
            stem = "safe_" + stem;
        }

        // Replace suspicious characters
        stem = SAFE_FILENAME_CHARS.matcher(stem).replaceAll("_");
        ext = SAFE_FILENAME_CHARS.matcher(ext).replaceAll("_");

        String result = stem + ext;
        if (result.length() > 200) {
            result = result.substring(0, 200);
        }
        return result.isBlank() ? "scan_upload" : result;
    }

    private String extractExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public String calculateSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest algorithm not available", e);
        }
    }
}
