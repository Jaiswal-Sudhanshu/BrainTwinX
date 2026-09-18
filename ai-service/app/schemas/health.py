from datetime import datetime, timezone
from typing import Dict, Optional
from pydantic import BaseModel, Field


class HealthResponse(BaseModel):
    status: str = "ok"
    service: str = "braintwinx-ai-service"
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class ReadyResponse(BaseModel):
    ready: bool
    preprocessingVersion: str
    modelsLoaded: Dict[str, bool]
    reason: Optional[str] = None
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
