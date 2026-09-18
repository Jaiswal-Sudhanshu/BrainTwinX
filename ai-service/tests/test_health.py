from pathlib import Path
import torch
from app.models.registry import ModelRegistry, model_registry


def test_liveness_endpoint_returns_200(client):
    response = client.get("/internal/ai/v1/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["service"] == "braintwinx-ai-service"
    assert "timestamp" in data


def test_readiness_fails_closed_when_weights_absent(client):
    # Standard state: no weights exist
    response = client.get("/internal/ai/v1/ready")
    assert response.status_code == 503
    data = response.json()
    assert data["ready"] is False
    assert data["reason"] is not None
    assert "NOT_FOUND" in data["reason"] or "MISSING" in data["reason"] or "WEIGHTS" in data["reason"]
    assert data["modelsLoaded"]["classifier"] is False
    assert data["preprocessingVersion"] == "1.0.0"


def test_readiness_succeeds_when_all_weights_present(tmp_path, monkeypatch, client):
    # Create fake valid weight files in tmp_path
    weights_dir = tmp_path / "weights"
    weights_dir.mkdir()

    from app.models.classifier import BrainTumorCNN
    torch.save({"state_dict": BrainTumorCNN().state_dict(), "version": "1.0.0"}, weights_dir / "classifier_cnn_v1.pt")
    for filename in ["segmentation_unet_v1.pt", "longitudinal_lstm_v1.pt"]:
        torch.save({"state_dict": {}, "version": "1.0.0"}, weights_dir / filename)

    custom_registry = ModelRegistry(weights_dir=weights_dir)
    custom_registry.load_all()
    assert custom_registry.is_ready is True

    # Monkeypatch singleton registry
    monkeypatch.setattr("app.api.v1.endpoints.model_registry", custom_registry)

    response = client.get("/internal/ai/v1/ready")
    assert response.status_code == 200
    data = response.json()
    assert data["ready"] is True
    assert data["reason"] is None
    assert all(data["modelsLoaded"].values())
