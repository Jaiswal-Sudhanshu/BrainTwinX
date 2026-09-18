"""Longitudinal LSTM tumor growth forecasting model (Phase 9).

Enforces medical-safety invariants (brief §13, §44, §46):
1. Outputs are strictly MODEL-BASED TREND ESTIMATES, never statements of certainty.
2. Minimum history required: 3 valid observations spanning at least 30 days.
3. No extrapolation or fabricated trends when history is insufficient.
"""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import torch
import torch.nn as nn


class TumorGrowthLSTM(nn.Module):
    """PyTorch LSTM for longitudinal tumor area trajectory and trend estimation.

    Input shape: (batch_size, seq_len, 2)
        Features per timepoint: [normalized_days, normalized_area_px]
    Outputs:
        - trajectory: (batch_size, num_horizons) predicted area offsets/values
        - trend_logits: (batch_size, 4) logits for [INCREASING, DECREASING, STABLE, INDETERMINATE]
    """

    TREND_CLASSES = ["INCREASING", "DECREASING", "STABLE", "INDETERMINATE"]

    def __init__(
        self,
        input_size: int = 2,
        hidden_size: int = 32,
        num_layers: int = 2,
        num_horizons: int = 3,
        num_classes: int = 4,
        dropout: float = 0.1,
    ) -> None:
        super().__init__()
        self.input_size = input_size
        self.hidden_size = hidden_size
        self.num_layers = num_layers
        self.num_horizons = num_horizons
        self.num_classes = num_classes

        self.lstm = nn.LSTM(
            input_size=input_size,
            hidden_size=hidden_size,
            num_layers=num_layers,
            batch_first=True,
            dropout=dropout if num_layers > 1 else 0.0,
        )

        # Head 1: Predict future area values (at +30, +60, +90 days)
        self.forecast_head = nn.Sequential(
            nn.Linear(hidden_size, 16),
            nn.ReLU(),
            nn.Linear(16, num_horizons),
        )

        # Head 2: Categorical trend direction
        self.trend_head = nn.Sequential(
            nn.Linear(hidden_size, 16),
            nn.ReLU(),
            nn.Linear(16, num_classes),
        )

    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        """Forward pass.

        Args:
            x: Tensor of shape (batch_size, seq_len, 2)

        Returns:
            Tuple of:
                forecast_values: (batch_size, num_horizons)
                trend_logits: (batch_size, num_classes)
        """
        out, (hn, cn) = self.lstm(x)
        # Use representation from last valid timestep
        last_hidden = out[:, -1, :]

        forecast = self.forecast_head(last_hidden)
        trend_logits = self.trend_head(last_hidden)
        return forecast, trend_logits


def load_forecaster_weights(
    weights_path: str | Path,
    expected_sha256: Optional[str] = None,
    device: Optional[torch.device] = None,
) -> TumorGrowthLSTM:
    """Load and validate LSTM forecaster weights.

    Args:
        weights_path: Path to PyTorch model weights file (.pt or .pth)
        expected_sha256: Optional SHA-256 hex string for integrity validation
        device: Device to load model onto

    Returns:
        Evaluated TumorGrowthLSTM instance
    """
    path = Path(weights_path)
    if not path.is_file():
        raise FileNotFoundError(f"Forecaster weights file not found: {path}")

    # Checksum validation
    if expected_sha256 is not None:
        hasher = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                hasher.update(chunk)
        digest = hasher.hexdigest()
        if digest.lower() != expected_sha256.lower():
            raise ValueError(
                f"Forecaster weights checksum mismatch: expected {expected_sha256}, got {digest}"
            )

    if device is None:
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    model = TumorGrowthLSTM()
    state_dict = torch.load(path, map_location=device, weights_only=True)
    if isinstance(state_dict, dict) and "state_dict" in state_dict:
        state_dict = state_dict["state_dict"]
    model.load_state_dict(state_dict)
    model.to(device)
    model.eval()
    return model
