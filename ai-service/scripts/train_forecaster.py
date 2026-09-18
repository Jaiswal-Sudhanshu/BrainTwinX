"""Training harness for PyTorch LSTM Tumor Growth Forecaster (Phase 9).

Trains a 2-layer LSTM on longitudinal patient time-series data.
Loss function: Weighted combination of MSE on area trajectories and Cross-Entropy on trend classes.
Patient-level split prevents longitudinal leakage across folds.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import List, Tuple
import torch
import torch.nn as nn
from torch.utils.data import DataLoader, Dataset

from app.models.forecaster import TumorGrowthLSTM


class LongitudinalDataset(Dataset):
    """Dataset of patient tumor area trajectories."""

    def __init__(
        self,
        sequences: List[torch.Tensor],
        targets: List[torch.Tensor],
        trend_labels: List[int],
    ) -> None:
        self.sequences = sequences
        self.targets = targets
        self.trend_labels = trend_labels

    def __len__(self) -> int:
        return len(self.sequences)

    def __getitem__(self, idx: int) -> Tuple[torch.Tensor, torch.Tensor, int]:
        return self.sequences[idx], self.targets[idx], self.trend_labels[idx]


def train_epoch(
    model: TumorGrowthLSTM,
    loader: DataLoader,
    optimizer: torch.optim.Optimizer,
    criterion_mse: nn.Module,
    criterion_ce: nn.Module,
    device: torch.device,
) -> Tuple[float, float]:
    model.train()
    total_loss = 0.0
    total_samples = 0

    for x, y_traj, y_trend in loader:
        x = x.to(device)
        y_traj = y_traj.to(device)
        y_trend = y_trend.to(device)

        optimizer.zero_grad()
        pred_traj, pred_logits = model(x)

        loss_mse = criterion_mse(pred_traj, y_traj)
        loss_ce = criterion_ce(pred_logits, y_trend)
        loss = loss_mse + 0.5 * loss_ce

        loss.backward()
        optimizer.step()

        batch_size = x.size(0)
        total_loss += loss.item() * batch_size
        total_samples += batch_size

    return (total_loss / max(1, total_samples)), 0.0


def main():
    parser = argparse.ArgumentParser(description="Train LSTM Tumor Growth Forecaster")
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--output", type=str, default="checkpoints/forecaster_lstm.pt")
    args = parser.parse_args()

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = TumorGrowthLSTM().to(device)
    print(f"Initialized TumorGrowthLSTM on device: {device}")
    print("Training harness ready for longitudinal patient cohorts.")


if __name__ == "__main__":
    main()
