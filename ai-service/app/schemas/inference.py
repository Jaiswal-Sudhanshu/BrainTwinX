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
    scanDate: str
    tumorAreaMm2: float


class LongitudinalRequest(BaseModel):
    patientCode: str
    observations: List[ObservationPoint]


class LongitudinalResponse(BaseModel):
    modelName: str
    modelVersion: str
    insufficientHistory: bool
    reason: Optional[str] = None
    growthRatePerMonth: Optional[float] = None
    estimatedDoublingTimeDays: Optional[float] = None
    forecastDays: Optional[int] = None
    projectedAreaMm2: Optional[float] = None
    isSynthetic: bool = False
