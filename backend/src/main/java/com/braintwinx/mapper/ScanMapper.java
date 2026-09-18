package com.braintwinx.mapper;

import com.braintwinx.dto.ScanResponse;
import com.braintwinx.entity.Scan;
import org.springframework.stereotype.Component;

/**
 * Converts {@link Scan} entities to API responses.
 *
 * <p>Hand-written and one-way: internal database ID, internal patient ID, storage key,
 * and creating user ID are never mapped to the public representation.
 */
@Component
public class ScanMapper {

    /**
     * Maps a {@link Scan} entity to a public-safe {@link ScanResponse}.
     *
     * @param scan the persistent entity
     * @return public-safe DTO
     */
    public ScanResponse toResponse(Scan scan) {
        return new ScanResponse(
                scan.getPublicId(),
                scan.getPatient().getPatientCode(),
                scan.getScanDate(),
                scan.getScanType(),
                scan.getOriginalFilename(),
                scan.getDetectedMimeType(),
                scan.getFileSizeBytes(),
                scan.getImageWidth(),
                scan.getImageHeight(),
                scan.getStatus(),
                scan.getFailureCode(),
                scan.getFailureReason(),
                scan.getCreatedAt(),
                scan.getUpdatedAt()
        );
    }
}
