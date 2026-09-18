"""Tests for Phase 9 Longitudinal Analysis & LSTM Forecasting."""

from pathlib import Path
import tempfile
import pytest
import torch
from fastapi import HTTPException

from app.config.settings import settings
from app.models.forecaster import TumorGrowthLSTM, load_forecaster_weights
from app.models.registry import model_registry
from app.inference.forecasting import run_growth_forecast
from app.schemas.inference import (
    ObservationPoint,
    LongitudinalForecastRequest,
    LongitudinalForecastResponse,
)
from scripts.evaluate_forecaster import compute_forecaster_metrics


def test_lstm_model_shapes():
    """Verify PyTorch LSTM model input and output tensor dimensions."""
    model = TumorGrowthLSTM(input_size=2, hidden_size=32, num_layers=2, num_horizons=3, num_classes=4)
    model.eval()

    # Batch of 2, 4 timesteps, 2 features [days, area]
    x = torch.randn(2, 4, 2)
    forecast, trend_logits = model(x)

    assert forecast.shape == (2, 3), f"Expected (2, 3), got {forecast.shape}"
    assert trend_logits.shape == (2, 4), f"Expected (2, 4), got {trend_logits.shape}"


def test_weights_loader_checksum_and_loading():
    """Verify load_forecaster_weights loads valid state_dict and enforces checksum."""
    model = TumorGrowthLSTM()
    with tempfile.NamedTemporaryFile(suffix=".pt", delete=False) as f:
        tmp_path = Path(f.name)

    try:
        torch.save(model.state_dict(), tmp_path)
        import hashlib
        with open(tmp_path, "rb") as f_in:
            valid_sha256 = hashlib.sha256(f_in.read()).hexdigest()

        loaded_model = load_forecaster_weights(tmp_path, expected_sha256=valid_sha256)
        assert isinstance(loaded_model, TumorGrowthLSTM)

        # Mismatched checksum must raise ValueError
        with pytest.raises(ValueError, match="checksum mismatch"):
            load_forecaster_weights(tmp_path, expected_sha256="0000000000000000000000000000000000000000000000000000000000000000")
    finally:
        if tmp_path.exists():
            tmp_path.unlink()


def test_forecaster_refuses_insufficient_observations():
    """Brief §13: Forecasting requires at least 3 historical observations."""
    req_2_obs = LongitudinalForecastRequest(
        patientCode="PAT-001",
        observations=[
            ObservationPoint(scanId="s1", scanDate="2026-01-01", daysFromFirst=0, tumorAreaPx=1000),
            ObservationPoint(scanId="s2", scanDate="2026-02-01", daysFromFirst=31, tumorAreaPx=1100),
        ],
    )

    with pytest.raises(HTTPException) as exc_info:
        run_growth_forecast(req_2_obs)

    assert exc_info.value.status_code == 400
    assert "Insufficient historical observations" in exc_info.value.detail


def test_fails_closed_when_weights_missing_and_stub_off(monkeypatch):
    """Safety Invariant: When weights are absent and stub inference is off, fails closed with 503."""
    monkeypatch.setattr(settings, "ALLOW_STUB_INFERENCE", False)
    monkeypatch.setattr(model_registry, "get_model", lambda key: None)

    req = LongitudinalForecastRequest(
        patientCode="PAT-001",
        observations=[
            ObservationPoint(scanId="s1", scanDate="2026-01-01", daysFromFirst=0, tumorAreaPx=1000),
            ObservationPoint(scanId="s2", scanDate="2026-02-01", daysFromFirst=31, tumorAreaPx=1100),
            ObservationPoint(scanId="s3", scanDate="2026-03-01", daysFromFirst=59, tumorAreaPx=1200),
        ],
    )

    with pytest.raises(HTTPException) as exc_info:
        run_growth_forecast(req)

    assert exc_info.value.status_code == 503
    assert "not ready or weights missing" in exc_info.value.detail


def test_stub_inference_when_enabled(monkeypatch):
    """Gated development stub tags output as synthetic and includes mandatory disclaimer."""
    monkeypatch.setattr(settings, "ALLOW_STUB_INFERENCE", True)
    monkeypatch.setattr(model_registry, "get_model", lambda key: None)

    req = LongitudinalForecastRequest(
        patientCode="PAT-001",
        observations=[
            ObservationPoint(scanId="s1", scanDate="2026-01-01", daysFromFirst=0, tumorAreaPx=1000),
            ObservationPoint(scanId="s2", scanDate="2026-02-01", daysFromFirst=31, tumorAreaPx=1100),
            ObservationPoint(scanId="s3", scanDate="2026-03-01", daysFromFirst=59, tumorAreaPx=1200),
        ],
    )

    resp = run_growth_forecast(req)
    assert isinstance(resp, LongitudinalForecastResponse)
    assert resp.isSynthetic is True
    assert resp.trendDirection == "INCREASING"
    assert len(resp.forecast) == 3
    assert resp.forecast[0].horizonDays == 30
    assert resp.forecast[1].horizonDays == 60
    assert resp.forecast[2].horizonDays == 90
    assert "MODEL-BASED TREND ESTIMATE" in resp.disclaimer


def test_real_model_inference_execution(monkeypatch):
    """Verify execution with a loaded TumorGrowthLSTM instance."""
    monkeypatch.setattr(settings, "ALLOW_STUB_INFERENCE", False)
    model = TumorGrowthLSTM()
    model.eval()
    monkeypatch.setattr(model_registry, "get_model", lambda key: model if key in ("forecaster", "longitudinal") else None)

    req = LongitudinalForecastRequest(
        patientCode="PAT-001",
        observations=[
            ObservationPoint(scanId="s1", scanDate="2026-01-01", daysFromFirst=0, tumorAreaPx=1500),
            ObservationPoint(scanId="s2", scanDate="2026-02-01", daysFromFirst=31, tumorAreaPx=1520),
            ObservationPoint(scanId="s3", scanDate="2026-03-01", daysFromFirst=62, tumorAreaPx=1550),
        ],
    )

    resp = run_growth_forecast(req)
    assert isinstance(resp, LongitudinalForecastResponse)
    assert resp.isSynthetic is False
    assert resp.modelName == "BrainTumorLSTM"
    assert resp.trendDirection in TumorGrowthLSTM.TREND_CLASSES
    assert len(resp.forecast) == 3
    assert "MODEL-BASED TREND ESTIMATE" in resp.disclaimer


def test_offline_metrics_computation():
    """Verify calculation of MAE, RMSE, MAPE, and trend accuracy in evaluation harness."""
    preds = [100.0, 150.0, 200.0]
    truth = [110.0, 140.0, 210.0]
    pred_trends = ["INCREASING", "STABLE", "DECREASING"]
    true_trends = ["INCREASING", "STABLE", "INCREASING"]

    metrics = compute_forecaster_metrics(preds, truth, pred_trends, true_trends)
    assert metrics["sample_count"] == 3
    assert metrics["mae_px"] == 10.0
    assert metrics["rmse_px"] == 10.0
    assert round(metrics["trend_accuracy"], 2) == 0.67
