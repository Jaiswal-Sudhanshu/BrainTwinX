import hashlib
import io
from pathlib import Path
from typing import Optional, Tuple

from PIL import Image
import torch
import torch.nn as nn


def compute_file_sha256(file_path: Path) -> str:
    """Computes SHA-256 hash of a file."""
    hasher = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()


class DoubleConv(nn.Module):
    """(Conv2D -> BatchNorm -> ReLU) * 2"""

    def __init__(self, in_channels: int, out_channels: int):
        super().__init__()
        self.block = nn.Sequential(
            nn.Conv2d(in_channels, out_channels, kernel_size=3, padding=1, bias=False),
            nn.BatchNorm2d(out_channels),
            nn.ReLU(inplace=True),
            nn.Conv2d(out_channels, out_channels, kernel_size=3, padding=1, bias=False),
            nn.BatchNorm2d(out_channels),
            nn.ReLU(inplace=True),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.block(x)


class BrainTumorUNet(nn.Module):
    """
    Standard U-Net architecture for single-channel brain MRI segmentation.
    Input: (B, 1, 224, 224)
    Output: (B, 1, 224, 224) binary probability mask in range [0, 1].
    """

    def __init__(self, in_channels: int = 1, out_channels: int = 1, base_features: int = 32):
        super().__init__()

        # Encoder (Contracting Path)
        self.inc = DoubleConv(in_channels, base_features)
        self.down1 = nn.Sequential(nn.MaxPool2d(2), DoubleConv(base_features, base_features * 2))
        self.down2 = nn.Sequential(nn.MaxPool2d(2), DoubleConv(base_features * 2, base_features * 4))
        self.down3 = nn.Sequential(nn.MaxPool2d(2), DoubleConv(base_features * 4, base_features * 8))

        # Bottleneck
        self.down4 = nn.Sequential(nn.MaxPool2d(2), DoubleConv(base_features * 8, base_features * 16))

        # Decoder (Expanding Path with Skip Connections)
        self.up1 = nn.ConvTranspose2d(base_features * 16, base_features * 8, kernel_size=2, stride=2)
        self.conv_up1 = DoubleConv(base_features * 16, base_features * 8)

        self.up2 = nn.ConvTranspose2d(base_features * 8, base_features * 4, kernel_size=2, stride=2)
        self.conv_up2 = DoubleConv(base_features * 8, base_features * 4)

        self.up3 = nn.ConvTranspose2d(base_features * 4, base_features * 2, kernel_size=2, stride=2)
        self.conv_up3 = DoubleConv(base_features * 4, base_features * 2)

        self.up4 = nn.ConvTranspose2d(base_features * 2, base_features, kernel_size=2, stride=2)
        self.conv_up4 = DoubleConv(base_features * 2, base_features)

        # Output head
        self.outc = nn.Sequential(
            nn.Conv2d(base_features, out_channels, kernel_size=1),
            nn.Sigmoid()
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # Encoder
        x1 = self.inc(x)
        x2 = self.down1(x1)
        x3 = self.down2(x2)
        x4 = self.down3(x3)
        x5 = self.down4(x4)

        # Decoder with skips
        x = self.up1(x5)
        x = torch.cat([x, x4], dim=1)
        x = self.conv_up1(x)

        x = self.up2(x)
        x = torch.cat([x, x3], dim=1)
        x = self.conv_up2(x)

        x = self.up3(x)
        x = torch.cat([x, x2], dim=1)
        x = self.conv_up3(x)

        x = self.up4(x)
        x = torch.cat([x, x1], dim=1)
        x = self.conv_up4(x)

        return self.outc(x)

    def predict_mask(
        self,
        tensor: torch.Tensor,
        threshold: float = 0.5,
        min_pixel_threshold: int = 5
    ) -> Tuple[torch.Tensor, bool, Optional[int], Optional[int], Optional[int], Optional[int], Optional[int]]:
        """
        Executes inference on a preprocessed (1, 1, 224, 224) tensor.
        Returns:
            binary_mask: 2D uint8 Tensor of shape (224, 224), values in {0, 255}
            tumor_detected: bool
            tumor_area_px: Optional[int] (number of positive mask pixels)
            bbox_x: Optional[int]
            bbox_y: Optional[int]
            bbox_width: Optional[int]
            bbox_height: Optional[int]
        """
        self.eval()
        with torch.no_grad():
            prob_map = self(tensor)  # (1, 1, H, W)

        mask_2d = (prob_map[0, 0] >= threshold).to(torch.uint8)  # (H, W)
        positive_indices = torch.nonzero(mask_2d)
        num_positive_pixels = int(positive_indices.shape[0])

        if num_positive_pixels < min_pixel_threshold:
            # Below noise threshold: no tumour detected
            empty_mask = torch.zeros_like(mask_2d, dtype=torch.uint8)
            return empty_mask, False, None, None, None, None, None

        # Calculate tight bounding box
        min_y = int(torch.min(positive_indices[:, 0]).item())
        max_y = int(torch.max(positive_indices[:, 0]).item())
        min_x = int(torch.min(positive_indices[:, 1]).item())
        max_x = int(torch.max(positive_indices[:, 1]).item())

        bbox_x = min_x
        bbox_y = min_y
        bbox_width = max_x - min_x + 1
        bbox_height = max_y - min_y + 1

        binary_mask_255 = mask_2d * 255
        return binary_mask_255, True, num_positive_pixels, bbox_x, bbox_y, bbox_width, bbox_height


def mask_tensor_to_png_bytes(mask_tensor: torch.Tensor) -> bytes:
    """Converts a (H, W) uint8 tensor into PNG bytes."""
    mask_np = mask_tensor.cpu().numpy().astype("uint8")
    img = Image.fromarray(mask_np, mode="L")
    buffer = io.BytesIO()
    img.save(buffer, format="PNG")
    return buffer.getvalue()


def load_segmenter_weights(weights_path: Path, expected_sha256: Optional[str] = None) -> BrainTumorUNet:
    """
    Loads BrainTumorUNet weights from disk with SHA-256 validation.
    """
    if not weights_path.exists():
        raise FileNotFoundError(f"Weight file not found at: {weights_path}")

    if expected_sha256 is not None:
        actual_sha = compute_file_sha256(weights_path)
        if actual_sha.lower() != expected_sha256.lower():
            raise ValueError(
                f"Model weights checksum mismatch for {weights_path.name}. "
                f"Expected: {expected_sha256}, Actual: {actual_sha}"
            )

    model = BrainTumorUNet()
    checkpoint = torch.load(weights_path, map_location=torch.device("cpu"), weights_only=True)
    state_dict = checkpoint.get("state_dict", checkpoint) if isinstance(checkpoint, dict) else checkpoint
    if state_dict:
        model.load_state_dict(state_dict)
    model.eval()

    # Input shape assertion
    dummy_input = torch.randn(1, 1, 224, 224)
    with torch.no_grad():
        out = model(dummy_input)
    if out.shape != (1, 1, 224, 224):
        raise ValueError(f"Unexpected output shape from segmenter: {out.shape}, expected (1, 1, 224, 224)")

    return model
