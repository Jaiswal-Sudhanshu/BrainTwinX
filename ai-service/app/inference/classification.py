import base64
from fastapi import HTTPException, status
from app.models.classifier import BrainTumorCNN
from app.models.registry import model_registry
from app.preprocessing.pipeline import PreprocessingPipeline
from app.schemas.inference import ClassificationRequest, ClassificationResponse

pipeline = PreprocessingPipeline()


def run_classification_inference(request: ClassificationRequest) -> ClassificationResponse:
    """
    Executes CNN classification on a brain MRI slice.
    """
    model = model_registry.get_model("classifier")
    if model is None or not isinstance(model, BrainTumorCNN):
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Tumor classifier model is not ready or weights missing"
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

    predicted_class, confidence, probabilities = model.predict_probabilities(preprocessed.tensor)

    return ClassificationResponse(
        modelName="BrainTumorCNN",
        modelVersion="1.0.0",
        preprocessingVersion=preprocessed.preprocessing_version,
        tumorType=predicted_class,
        confidence=confidence,
        probabilities=probabilities,
        isSynthetic=False
    )
