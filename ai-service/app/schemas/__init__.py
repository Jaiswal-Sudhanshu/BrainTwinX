from .health import HealthResponse, ReadyResponse
from .inference import (
    ClassificationRequest,
    ClassificationResponse,
    SegmentationRequest,
    SegmentationResponse,
    ObservationPoint,
    ForecastPoint,
    LongitudinalForecastRequest,
    LongitudinalForecastResponse,
)

# Aliases for backward compatibility
LongitudinalRequest = LongitudinalForecastRequest
LongitudinalResponse = LongitudinalForecastResponse

__all__ = [
    "HealthResponse",
    "ReadyResponse",
    "ClassificationRequest",
    "ClassificationResponse",
    "SegmentationRequest",
    "SegmentationResponse",
    "ObservationPoint",
    "ForecastPoint",
    "LongitudinalForecastRequest",
    "LongitudinalForecastResponse",
    "LongitudinalRequest",
    "LongitudinalResponse",
]

