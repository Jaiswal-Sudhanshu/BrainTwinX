"""
Training script for BrainTwinX U-Net Brain Tumor Segmentation.
Supports patient-level train/validation split to avoid data leakage.
Utilizes combined BCE + Soft Dice Loss for class-imbalanced tumor masks.
"""

from pathlib import Path
from typing import Dict, List, Tuple
import torch
import torch.nn as nn
import torch.optim as optim

from app.models.segmenter import BrainTumorUNet


class DiceLoss(nn.Module):
    """Soft Dice Loss for segmentation."""

    def __init__(self, smooth: float = 1e-6):
        super().__init__()
        self.smooth = smooth

    def forward(self, pred: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
        pred = pred.contiguous()
        target = target.contiguous()

        intersection = (pred * target).sum(dim=2).sum(dim=2)
        cardinality = (pred.pow(2) + target.pow(2)).sum(dim=2).sum(dim=2)

        dice = (2.0 * intersection + self.smooth) / (cardinality + self.smooth)
        return 1.0 - dice.mean()


class BCEDiceLoss(nn.Module):
    """Combination of Binary Cross-Entropy and Soft Dice Loss."""

    def __init__(self, bce_weight: float = 0.5):
        super().__init__()
        self.bce_weight = bce_weight
        self.bce = nn.BCELoss()
        self.dice = DiceLoss()

    def forward(self, pred: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
        return self.bce_weight * self.bce(pred, target) + (1.0 - self.bce_weight) * self.dice(pred, target)


def partition_patients(
    patient_ids: List[str], val_ratio: float = 0.2, seed: int = 42
) -> Tuple[List[str], List[str]]:
    """Partitions patient IDs to prevent scan leakage between splits."""
    import random
    rng = random.Random(seed)
    shuffled = list(set(patient_ids))
    rng.shuffle(shuffled)
    n_val = max(1, int(len(shuffled) * val_ratio))
    val_patients = set(shuffled[:n_val])
    train_patients = set(shuffled[n_val:])
    return sorted(list(train_patients)), sorted(list(val_patients))


if __name__ == "__main__":
    print("BrainTwinX — U-Net Segmentation Training Harness")
    print("Supports patient-level splits and BCE-Dice compound loss.")
    print("To train on your local dataset, follow docs/DATASET_SETUP.md.")
