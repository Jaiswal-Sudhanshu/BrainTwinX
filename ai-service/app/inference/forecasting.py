"""Inference handler for longitudinal tumor growth forecasting (Phase 9).

Enforces medical-safety invariants (brief §13, §44, §46):
- Refuses to run if observations < 3.
- Fails closed with HTTP 503 if weights are missing in production.
- Strictly qualifies output as MODEL-BASED TREND ESTIMATE.
"""

from __future__ import annotations

from typing import List
from fastapi import HTTPException, status
import torch

from app.config.settings import settings
from app.models.forecaster import TumorGrowthLSTM
from app.models.registry import model_registry
from app.schemas.inference import (
    ForecastPoint,
    LongitudinalForecastRequest,
    LongitudinalForecastResponse,
)

MIN_OBSERVATIONS = 3
NORMALIZATION_DAYS = 365.0
NORMALIZATION_AREA = 50176.0  # 224 * 224 preprocessed resolution max area


def run_growth_forecast(request: LongitudinalForecastRequest) -> LongitudinalForecastResponse:
    """Executes longitudinal forecasting on a series of patient observations."""
    if len(request.observations) < MIN_OBSERVATIONS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                f"Insufficient historical observations: minimum {MIN_OBSERVATIONS} required, "
                f"got {len(request.observations)}. INSUFFICIENT_HISTORY should be handled before AI inference."
            ),
        )

    # Sort observations chronologically by daysFromFirst
    sorted_obs = sorted(request.observations, key=lambda o: o.daysFromFirst)

    model = model_registry.get_model("forecaster")
    is_stub_allowed = getattr(settings, "ALLOW_STUB_INFERENCE", False)

    if (model is None or not isinstance(model, TumorGrowthLSTM)) and not is_stub_allowed:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Tumor growth forecaster model is not ready or weights missing",
        )

    horizons = request.forecastHorizonsDays or [30, 60, 90]
    latest_obs = sorted_obs[-1]
    latest_area = latest_obs.tumorAreaPx

    if model is None or not isinstance(model, TumorGrowthLSTM):
        # Development stub inference (explicitly synthetic)
        # Compute trajectory slope across the observed timepoints
        first_obs = sorted_obs[0]
        day_diff = max(1, latest_obs.daysFromFirst - first_obs.daysFromFirst)
        area_diff = latest_obs.tumorAreaPx - first_obs.tumorAreaPx
        rate_per_day = area_diff / day_diff

        if rate_per_day > 0.5:
            trend_direction = "INCREASING"
        elif rate_per_day < -0.5:
            trend_direction = "DECREASING"
        else:
            trend_direction = "STABLE"

        forecast_points: List[ForecastPoint] = []
        for h in horizons:
            projected = max(0, int(round(latest_area + rate_per_day * h)))
            lower = max(0, int(round(projected * 0.9)))
            upper = int(round(projected * 1.1))
            forecast_points.append(
                ForecastPoint(
                    horizonDays=h,
                    projectedAreaPx=projected,
                    projectedLowerPx=lower,
                    projectedUpperPx=upper,
                )
            )

        return LongitudinalForecastResponse(
            modelName="BrainTumorLSTM",
            modelVersion="1.0.0",
            preprocessingVersion="1.0.0",
            trendDirection=trend_direction,
            forecast=forecast_points,
            disclaimer="MODEL-BASED TREND ESTIMATE. Not a clinical diagnosis or guarantee of future growth.",
            isSynthetic=True,
        )

    # Real model inference with TumorGrowthLSTM
    model.eval()
    features = []
    for obs in sorted_obs:
        norm_days = obs.daysFromFirst / NORMALIZATION_DAYS
        norm_area = obs.tumorAreaPx / NORMALIZATION_AREA
        features.append([norm_days, norm_area])

    input_tensor = torch.tensor([features], dtype=torch.float32)  # (1, seq_len, 2)

    with torch.no_grad():
        pred_offsets, trend_logits = model(input_tensor)
        predicted_trend_idx = int(torch.argmax(trend_logits[0]).item())
        trend_direction = TumorGrowthLSTM.TREND_CLASSES[predicted_trend_idx]

        forecast_points = []
        for i, h in enumerate(horizons):
            # Model outputs normalized area delta or normalized area directly
            if i < pred_offsets.shape[1]:
                raw_val = pred_offsets[0, i].item()
                projected = max(0, int(round(latest_area + raw_val * NORMALIZATION_AREA)))
            else:
                projected = latest_area

            lower = max(0, int(round(projected * 0.9)))
            upper = int(round(projected * 1.1))
            forecast_points.append(
                ForecastPoint(
                    horizonDays=h,
                    projectedAreaPx=projected,
                    projectedLowerPx=lower,
                    projectedUpperPx=upper,
                )
            )

    return LongitudinalForecastResponse(
        modelName="BrainTumorLSTM",
        modelVersion="1.0.0",
        preprocessingVersion="1.0.0",
        trendDirection=trend_direction,
        forecast=forecast_points,
        disclaimer="MODEL-BASED TREND ESTIMATE. Not a clinical diagnosis or guarantee of future growth.",
        isSynthetic=False,
    )
