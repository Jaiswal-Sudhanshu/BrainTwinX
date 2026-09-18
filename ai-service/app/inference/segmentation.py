import base64
import io
from fastapi import HTTPException, status
from PIL import Image, ImageDraw
import torch

from app.config.settings import settings
from app.models.registry import model_registry
from app.models.segmenter import BrainTumorUNet, mask_tensor_to_png_bytes
from app.preprocessing.pipeline import PreprocessingPipeline
from app.schemas.inference import SegmentationRequest, SegmentationResponse

pipeline = PreprocessingPipeline()


def _generate_synthetic_mask() -> bytes:
    """Generates a synthetic binary mask PNG for development/testing."""
    img = Image.new("L", (224, 224), color=0)
    draw = ImageDraw.Draw(img)
    # Draw a 40x40 circle at (90, 90) -> (130, 130)
    draw.ellipse([90, 90, 130, 130], fill=255)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def run_segmentation_inference(request: SegmentationRequest) -> SegmentationResponse:
    """
    Executes U-Net segmentation on a brain MRI slice.
    """
    model = model_registry.get_model("segmenter")
    is_stub_allowed = getattr(settings, "ALLOW_STUB_INFERENCE", False)

    if (model is None or not isinstance(model, BrainTumorUNet)) and not is_stub_allowed:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Tumor segmenter model is not ready or weights missing"
        )

    try:
        raw_bytes = base64.b64decode(request.imageBase64)
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid base64 image data: {e}"
        ) from e

    try:
        preprocessed = pipeline.preprocess_image_bytes(raw_bytes)
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Image preprocessing failed: {e}"
        ) from e

    if model is None or not isinstance(model, BrainTumorUNet):
        # Development stub mode: explicitly tagged synthetic and non-clinical
        mask_png_bytes = _generate_synthetic_mask()
        mask_b64 = base64.b64encode(mask_png_bytes).decode("utf-8")
        return SegmentationResponse(
            modelName="BrainTumorUNet-Stub",
            modelVersion="1.0.0-stub",
            preprocessingVersion=preprocessed.preprocessing_version,
            tumorDetected=True,
            tumorAreaPx=1256,
            maskWidth=224,
            maskHeight=224,
            bboxX=90,
            bboxY=90,
            bboxWidth=41,
            bboxHeight=41,
            maskBase64=mask_b64,
            isSynthetic=True
        )

    # Real inference execution
    mask_tensor, detected, area_px, bx, by, bw, bh = model.predict_mask(preprocessed.tensor)

    mask_b64 = None
    if detected and mask_tensor is not None:
        mask_png_bytes = mask_tensor_to_png_bytes(mask_tensor)
        mask_b64 = base64.b64encode(mask_png_bytes).decode("utf-8")

    return SegmentationResponse(
        modelName="BrainTumorUNet",
        modelVersion="1.0.0",
        preprocessingVersion=preprocessed.preprocessing_version,
        tumorDetected=detected,
        tumorAreaPx=area_px,
        maskWidth=224 if detected else None,
        maskHeight=224 if detected else None,
        bboxX=bx,
        bboxY=by,
        bboxWidth=bw,
        bboxHeight=bh,
        maskBase64=mask_b64,
        isSynthetic=False
    )
