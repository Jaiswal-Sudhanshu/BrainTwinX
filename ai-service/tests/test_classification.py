import base64
import io
from pathlib import Path
import pytest
from PIL import Image
import torch

from app.models.classifier import BrainTumorCNN, CLASS_NAMES, compute_file_sha256, load_classifier_weights
from app.schemas.inference import ClassificationRequest


def create_test_image_base64() -> str:
    img = Image.new("L", (100, 100), color=128)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("utf-8")


def test_classifier_output_properties_and_probabilities_sum():
    model = BrainTumorCNN()
    model.eval()

    # Input tensor shape: (1, 1, 224, 224)
    x = torch.randn(1, 1, 224, 224)
    predicted_class, confidence, probabilities = model.predict_probabilities(x)

    assert predicted_class in CLASS_NAMES
    assert 0.0 <= confidence <= 1.0
    assert len(probabilities) == 4
    for c in CLASS_NAMES:
        assert c in probabilities
        assert 0.0 <= probabilities[c] <= 1.0

    # Probabilities sum to 1.0 (within float precision)
    assert pytest.approx(sum(probabilities.values()), abs=1e-5) == 1.0


def test_classification_deterministic_for_fixed_weights_and_tensor():
    torch.manual_seed(42)
    model = BrainTumorCNN()
    model.eval()

    torch.manual_seed(123)
    x = torch.randn(1, 1, 224, 224)

    _, conf1, probs1 = model.predict_probabilities(x)
    _, conf2, probs2 = model.predict_probabilities(x)

    assert conf1 == conf2
    assert probs1 == probs2


def test_checksum_mismatch_aborts_weight_load(tmp_path):
    weight_file = tmp_path / "model.pt"
    torch.save(BrainTumorCNN().state_dict(), weight_file)

    actual_sha = compute_file_sha256(weight_file)
    wrong_sha = "0000000000000000000000000000000000000000000000000000000000000000"

    with pytest.raises(ValueError, match="checksum mismatch"):
        load_classifier_weights(weight_file, expected_sha256=wrong_sha)

    # Valid checksum loads successfully
    loaded = load_classifier_weights(weight_file, expected_sha256=actual_sha)
    assert isinstance(loaded, BrainTumorCNN)


def test_predict_endpoint_returns_503_when_model_unready(client):
    req = ClassificationRequest(
        scanId="scan-123",
        patientCode="PT-1",
        imageBase64=create_test_image_base64()
    )
    response = client.post("/internal/ai/v1/predict", json=req.model_dump())
    assert response.status_code == 503
    assert "not ready" in response.json()["detail"]


def test_predict_endpoint_succeeds_when_model_loaded(client, monkeypatch):
    from app.models.registry import model_registry

    # Create and register a model instance directly
    mock_model = BrainTumorCNN()
    mock_model.eval()

    monkeypatch.setattr(model_registry, "get_model", lambda key: mock_model if key == "classifier" else None)

    req = ClassificationRequest(
        scanId="scan-test",
        patientCode="PT-TEST",
        imageBase64=create_test_image_base64()
    )
    response = client.post("/internal/ai/v1/predict", json=req.model_dump())
    assert response.status_code == 200
    data = response.json()

    assert data["modelName"] == "BrainTumorCNN"
    assert data["modelVersion"] == "1.0.0"
    assert data["preprocessingVersion"] == "1.0.0"
    assert data["tumorType"] in CLASS_NAMES
    assert 0.0 <= data["confidence"] <= 1.0
    assert len(data["probabilities"]) == 4
    assert pytest.approx(sum(data["probabilities"].values()), abs=1e-4) == 1.0
    assert data["isSynthetic"] is False


def test_stub_inference_is_off_by_default(client):
    from app.config.settings import settings
    assert settings.ALLOW_STUB_INFERENCE is False

    req = ClassificationRequest(
        scanId="scan-stub-test",
        patientCode="PT-STUB",
        imageBase64=create_test_image_base64()
    )
    # When weights missing and stub is OFF (default), fails closed with 503
    response = client.post("/internal/ai/v1/predict", json=req.model_dump())
    assert response.status_code == 503


def test_stub_inference_when_explicitly_enabled(client, monkeypatch):
    from app.config.settings import settings
    monkeypatch.setattr(settings, "ALLOW_STUB_INFERENCE", True)

    req = ClassificationRequest(
        scanId="scan-stub-test",
        patientCode="PT-STUB",
        imageBase64=create_test_image_base64()
    )
    response = client.post("/internal/ai/v1/predict", json=req.model_dump())
    assert response.status_code == 200
    data = response.json()
    assert data["isSynthetic"] is True
    assert "Stub" in data["modelName"]
    assert 0.0 <= data["confidence"] <= 1.0

