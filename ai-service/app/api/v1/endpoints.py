from fastapi import APIRouter, Depends, Response, status
from app.api.deps import verify_internal_api_key
from app.config.settings import settings
from app.models.registry import model_registry
from app.schemas.health import HealthResponse, ReadyResponse
from app.schemas.inference import ClassificationRequest, ClassificationResponse

router = APIRouter()


@router.get("/health", response_model=HealthResponse, tags=["Observability"])
def health_check():
    """
    Liveness probe. Always returns 200 when the process is up.
    Publicly accessible internally (no auth required for probe runners).
    """
    return HealthResponse()


@router.get("/ready", response_model=ReadyResponse, tags=["Observability"])
def readiness_check(response: Response):
    """
    Readiness probe.
    Returns 200 OK only if all required model weights are present and loaded.
    Returns 503 Service Unavailable when weights are absent or corrupt.
    """
    is_ready = model_registry.is_ready
    if not is_ready:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE

    return ReadyResponse(
        ready=is_ready,
        preprocessingVersion=settings.PREPROCESSING_VERSION,
        modelsLoaded=model_registry.loaded_status,
        reason=model_registry.unready_reason,
    )


@router.post(
    "/predict",
    response_model=ClassificationResponse,
    dependencies=[Depends(verify_internal_api_key)],
    tags=["Inference"]
)
def predict_tumor(request: ClassificationRequest):
    """
    Classifies a brain MRI slice into one of 4 categories:
    glioma, meningioma, pituitary, no_tumor.
    """
    from app.inference.classification import run_classification_inference
    return run_classification_inference(request)
