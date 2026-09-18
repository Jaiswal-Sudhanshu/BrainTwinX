import io
import numpy as np
from PIL import Image
import torch
from app.preprocessing.pipeline import PreprocessingPipeline


def create_test_image_bytes(width=128, height=128, mode="RGB"):
    img = Image.new(mode, (width, height), color=(120, 80, 40) if mode == "RGB" else 120)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def test_preprocessing_deterministic_for_identical_input():
    pipeline = PreprocessingPipeline(target_size=224, version="1.0.0")
    img_bytes = create_test_image_bytes(100, 100, mode="L")

    out1 = pipeline.preprocess_image_bytes(img_bytes)
    out2 = pipeline.preprocess_image_bytes(img_bytes)

    assert torch.equal(out1.tensor, out2.tensor), "Preprocessing must be bitwise identical for identical input"
    assert out1.preprocessing_version == "1.0.0"
    assert out1.target_size == (224, 224)
    assert out1.original_size == (100, 100)


def test_preprocessing_output_shape_and_channels():
    pipeline = PreprocessingPipeline(target_size=224)

    # Grayscale image
    gray_bytes = create_test_image_bytes(80, 60, mode="L")
    out_gray = pipeline.preprocess_image_bytes(gray_bytes)
    assert out_gray.tensor.shape == (1, 1, 224, 224)
    assert out_gray.original_size == (80, 60)

    # RGB image converted to single-channel MRI input
    rgb_bytes = create_test_image_bytes(150, 150, mode="RGB")
    out_rgb = pipeline.preprocess_image_bytes(rgb_bytes)
    assert out_rgb.tensor.shape == (1, 1, 224, 224)
    assert out_rgb.original_size == (150, 150)


def test_preprocessing_handles_invalid_bytes():
    pipeline = PreprocessingPipeline()
    corrupt_bytes = b"not-a-valid-image-stream"

    import pytest
    with pytest.raises(ValueError, match="Failed to decode MRI image"):
        pipeline.preprocess_image_bytes(corrupt_bytes)
