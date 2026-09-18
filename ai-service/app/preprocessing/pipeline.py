import io
from dataclasses import dataclass
from typing import Tuple
import numpy as np
from PIL import Image
import torch

from app.config.settings import settings


@dataclass(frozen=True)
class PreprocessedOutput:
    tensor: torch.Tensor          # Shape: (1, 1, H, W) or (1, 3, H, W)
    original_size: Tuple[int, int] # (width, height)
    target_size: Tuple[int, int]   # (target_width, target_height)
    preprocessing_version: str
    mean: float
    std: float


class PreprocessingPipeline:
    """
    Deterministic MRI image preprocessing pipeline.

    Guarantees:
    1. Deterministic resizing and channel alignment.
    2. Version-tagged output matching training time settings.
    3. Normalization with fixed bounds.
    """

    def __init__(self, target_size: int = settings.TARGET_IMAGE_SIZE, version: str = settings.PREPROCESSING_VERSION):
        self.target_size = (target_size, target_size)
        self.version = version

    def preprocess_image_bytes(self, image_bytes: bytes) -> PreprocessedOutput:
        """
        Preprocesses raw image bytes deterministically into a PyTorch tensor.
        """
        try:
            with Image.open(io.BytesIO(image_bytes)) as img:
                return self.preprocess_pil_image(img)
        except Exception as e:
            raise ValueError(f"Failed to decode MRI image: {e}") from e

    def preprocess_pil_image(self, img: Image.Image) -> PreprocessedOutput:
        """
        Converts PIL image to normalized tensor.
        """
        original_size = (img.width, img.height)

        # Standardize to grayscale ('L') for consistency in single-slice MRI analysis
        if img.mode != "L":
            img = img.convert("L")

        # Deterministic resize using LANCZOS / BILINEAR
        resized = img.resize(self.target_size, Image.Resampling.BILINEAR)

        # Convert to numpy array float32 in range [0, 1]
        arr = np.asarray(resized, dtype=np.float32) / 255.0

        mean_val = float(np.mean(arr))
        std_val = float(np.std(arr)) if np.std(arr) > 1e-6 else 1.0

        # Normalization: zero-center with unit variance or min-max
        normalized = (arr - settings.NORM_MEAN) / settings.NORM_STD

        # Shape: (1, 1, H, W) batch dimension + channel dimension
        tensor = torch.from_numpy(normalized).unsqueeze(0).unsqueeze(0)

        return PreprocessedOutput(
            tensor=tensor,
            original_size=original_size,
            target_size=self.target_size,
            preprocessing_version=self.version,
            mean=mean_val,
            std=std_val
        )
