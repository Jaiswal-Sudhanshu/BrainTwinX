package com.braintwinx.mapper;

import com.braintwinx.dto.SegmentationResponse;
import com.braintwinx.entity.SegmentationResult;
import org.springframework.stereotype.Component;

@Component
public class SegmentationMapper {

    public SegmentationResponse toResponse(SegmentationResult result) {
        if (result == null) {
            return null;
        }

        return new SegmentationResponse(
                result.getPublicId(),
                result.getScan().getPublicId(),
                result.isTumorDetected(),
                result.getTumorAreaPx(),
                result.getMaskWidth(),
                result.getMaskHeight(),
                result.getBboxX(),
                result.getBboxY(),
                result.getBboxWidth(),
                result.getBboxHeight(),
                result.getMaskStorageKey() != null,
                result.getModelVersion() != null ? result.getModelVersion().getModelName() : "BrainTumorUNet",
                result.getModelVersion() != null ? result.getModelVersion().getModelVersion() : "1.0.0",
                result.getPreprocessingVersion(),
                result.isSynthetic(),
                result.getInferenceTimestamp(),
                result.getCreatedAt()
        );
    }
}
