import base64
import io
from pathlib import Path
from PIL import Image
import numpy as np
import pytest
import torch

from app.models.segmenter import (
    BrainTumorUNet, compute_file_sha256, load_segmenter_weights, mask_tensor_to_png_bytes
)
from app.schemas.inference import SegmentationRequest, SegmentationResponse
from scripts.evaluate_segmenter import compute_dice_coefficient, compute_iou, compute_segmentation_metrics


def create_test_image_base64() -> str:
    img = Image.new("L", (100, 100), color=128)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("utf-8")


def test_unet_forward_pass_and_output_shape():
    model = BrainTumorUNet()
    model.eval()

    x = torch.randn(1, 1, 224, 224)
    with torch.no_grad():
        out = model(x)

    assert out.shape == (1, 1, 224, 224)
    assert torch.all(out >= 0.0) and torch.all(out <= 1.0)


def test_predict_mask_tumor_detection_and_bounding_box():
    model = BrainTumorUNet()
    model.eval()

    # Create synthetic probability map with a 30x30 square at (50, 60) -> (79, 89)
    synthetic_prob = torch.zeros(1, 1, 224, 224)
    synthetic_prob[0, 0, 60:90, 50:80] = 0.95

    # Override forward temporarily to return synthetic_prob
    orig_forward = model.forward
    model.forward = lambda x: synthetic_prob

    try:
        dummy_input = torch.randn(1, 1, 224, 224)
        mask, detected, area_px, bx, by, bw, bh = model.predict_mask(dummy_input, threshold=0.5)

        assert detected is True
        assert area_px == 30 * 30
        assert bx == 50
        assert by == 60
        assert bw == 30
        assert bh == 30
        assert mask.shape == (224, 224)
        assert torch.max(mask) == 255
    finally:
        model.forward = orig_forward


def test_predict_mask_empty_when_no_tumor():
    model = BrainTumorUNet()
    model.eval()

    zero_prob = torch.zeros(1, 1, 224, 224)
    orig_forward = model.forward
    model.forward = lambda x: zero_prob

    try:
        dummy_input = torch.randn(1, 1, 224, 224)
        mask, detected, area_px, bx, by, bw, bh = model.predict_mask(dummy_input, threshold=0.5)

        assert detected is False
        assert area_px is None
        assert bx is None
        assert by is None
        assert bw is None
        assert bh is None
        assert torch.sum(mask) == 0
    finally:
        model.forward = orig_forward


def test_segmenter_checksum_mismatch_aborts_load(tmp_path):
    weight_file = tmp_path / "unet_model.pt"
    torch.save(BrainTumorUNet().state_dict(), weight_file)

    actual_sha = compute_file_sha256(weight_file)
    wrong_sha = "0000000000000000000000000000000000000000000000000000000000000000"

    with pytest.raises(ValueError, match="checksum mismatch"):
        load_segmenter_weights(weight_file, expected_sha256=wrong_sha)

    loaded = load_segmenter_weights(weight_file, expected_sha256=actual_sha)
    assert isinstance(loaded, BrainTumorUNet)


def test_segment_endpoint_returns_503_when_model_unready(client):
    req = SegmentationRequest(
        scanId="scan-123",
        patientCode="PT-1",
        imageBase64=create_test_image_base64()
    )
    response = client.post("/internal/ai/v1/segment", json=req.model_dump())
    assert response.status_code == 503
    assert "not ready" in response.json()["detail"]


def test_segment_endpoint_with_stub_enabled(client, monkeypatch):
    from app.config.settings import settings
    monkeypatch.setattr(settings, "ALLOW_STUB_INFERENCE", True)

    req = SegmentationRequest(
        scanId="scan-stub",
        patientCode="PT-STUB",
        imageBase64=create_test_image_base64()
    )
    response = client.post("/internal/ai/v1/segment", json=req.model_dump())
    assert response.status_code == 200
    data = response.json()

    assert data["isSynthetic"] is True
    assert data["tumorDetected"] is True
    assert data["tumorAreaPx"] is not None
    assert data["maskBase64"] is not None


def test_production_schema_has_no_dice_or_iou():
    """
    CRITICAL MEDICAL SAFETY:
    Dice and IoU must not be present in the production inference response schema.
    """
    schema_fields = SegmentationResponse.model_fields.keys()
    assert "dice" not in schema_fields
    assert "dice_score" not in schema_fields
    assert "iou" not in schema_fields
    assert "iou_score" not in schema_fields
    assert "tumorAreaMm2" not in schema_fields


def test_offline_evaluation_metrics():
    """
    Verifies offline evaluation calculations.
    """
    gt = np.zeros((100, 100), dtype=np.uint8)
    gt[20:60, 20:60] = 1  # 40x40 = 1600 pixels

    # Perfect prediction
    pred_perfect = gt.copy()
    assert compute_dice_coefficient(pred_perfect, gt) == pytest.approx(1.0)
    assert compute_iou(pred_perfect, gt) == pytest.approx(1.0)

    # Completely disjoint prediction
    pred_disjoint = np.zeros((100, 100), dtype=np.uint8)
    pred_disjoint[70:90, 70:90] = 1
    assert compute_dice_coefficient(pred_disjoint, gt) == pytest.approx(0.0, abs=1e-3)
    assert compute_iou(pred_disjoint, gt) == pytest.approx(0.0, abs=1e-3)

    metrics = compute_segmentation_metrics(pred_perfect, gt)
    assert metrics["dice"] == pytest.approx(1.0)
    assert metrics["sensitivity"] == pytest.approx(1.0)
    assert metrics["specificity"] == pytest.approx(1.0)
