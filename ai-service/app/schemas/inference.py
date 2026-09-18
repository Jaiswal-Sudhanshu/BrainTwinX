from typing import Dict, List, Optional
from pydantic import BaseModel, Field


class ClassificationRequest(BaseModel):
    scanId: str
    patientCode: str
    imageBase64: str = Field(..., description="Base64-encoded MRI slice (PNG or JPEG)")


class ClassificationResponse(BaseModel):
    modelName: str
    modelVersion: str
    preprocessingVersion: str
    tumorType: str
    confidence: float
    probabilities: Dict[str, float]
    isSynthetic: bool = False


class SegmentationRequest(BaseModel):
    scanId: str
    patientCode: str
    imageBase64: str = Field(..., description="Base64-encoded MRI slice (PNG or JPEG)")


class SegmentationResponse(BaseModel):
    modelName: str
    modelVersion: str
    preprocessingVersion: str
    tumorDetected: bool
    tumorAreaPx: Optional[int] = Field(None, description="Tumor area in preprocessed image pixels")
    maskWidth: Optional[int] = None
    maskHeight: Optional[int] = None
    bboxX: Optional[int] = None
    bboxY: Optional[int] = None
    bboxWidth: Optional[int] = None
    bboxHeight: Optional[int] = None
    maskBase64: Optional[str] = Field(None, description="Base64-encoded PNG binary mask (224x224)")
    isSynthetic: bool = False


class ObservationPoint(BaseModel):
    scanId: str
    scanDate: str
    daysFromFirst: int
    tumorAreaPx: int = Field(..., description="Tumor area in preprocessed image pixels")


class ForecastPoint(BaseModel):
    horizonDays: int = Field(..., description="Future offset in days from latest scan")
    projectedAreaPx: int = Field(..., description="Model-estimated tumor area in pixels")
    projectedLowerPx: Optional[int] = None
    projectedUpperPx: Optional[int] = None


class LongitudinalForecastRequest(BaseModel):
    patientCode: str
    observations: List[ObservationPoint]
    forecastHorizonsDays: List[int] = Field(default_factory=lambda: [30, 60, 90])


class LongitudinalForecastResponse(BaseModel):
    modelName: str
    modelVersion: str
    preprocessingVersion: str
    trendDirection: str = Field(..., description="INCREASING, DECREASING, STABLE, or INDETERMINATE")
    forecast: List[ForecastPoint]
    disclaimer: str = Field(
        default="MODEL-BASED TREND ESTIMATE. Not a clinical diagnosis or guarantee of future growth.",
        description="Mandatory qualification disclaimer per brief §13 and §44",
    )
    isSynthetic: bool = False

