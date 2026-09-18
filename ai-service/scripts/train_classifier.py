"""
BrainTwinX — CNN Classifier Training Script
Trains BrainTumorCNN on brain MRI slices with strict patient-level splitting
to prevent data leakage across slices.
"""

import argparse
import hashlib
import json
import logging
import os
from pathlib import Path
import random
import sys
from typing import Dict, List, Tuple

import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader
from PIL import Image
import numpy as np

# Ensure app package is importable
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.models.classifier import BrainTumorCNN, CLASS_NAMES
from app.preprocessing.pipeline import PreprocessingPipeline

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("train_classifier")


class BrainMRIDataset(Dataset):
    """
    Dataset of MRI slices loaded from disk and transformed through PreprocessingPipeline.
    """

    def __init__(self, samples: List[Tuple[Path, int]], pipeline: PreprocessingPipeline):
        self.samples = samples
        self.pipeline = pipeline

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, idx: int) -> Tuple[torch.Tensor, int]:
        path, label = self.samples[idx]
        with open(path, "rb") as f:
            raw_bytes = f.read()

        tensor = self.pipeline.preprocess_slice(raw_bytes)
        return tensor.squeeze(0), label


def patient_level_split(
    data_dir: Path,
    train_ratio: float = 0.70,
    val_ratio: float = 0.15,
    seed: int = 42
) -> Tuple[List[Tuple[Path, int]], List[Tuple[Path, int]], List[Tuple[Path, int]]]:
    """
    Groups samples by patient identifier to guarantee that all slices from the same
    patient reside entirely within a single split (train, val, or test).
    
    Expected file convention:
        {data_dir}/{class_name}/{patient_id}_{slice_num}.[jpg|png]
        or {data_dir}/{patient_id}/{class_name}/...
    """
    random.seed(seed)
    class_to_idx = {name: idx for idx, name in enumerate(CLASS_NAMES)}

    # Map patient_id -> list of (file_path, class_idx)
    patient_samples: Dict[str, List[Tuple[Path, int]]] = {}

    for class_name in CLASS_NAMES:
        class_dir = data_dir / class_name
        if not class_dir.exists():
            continue
        for file_path in class_dir.glob("*.*"):
            if file_path.suffix.lower() not in [".png", ".jpg", ".jpeg"]:
                continue
            # Extract patient ID prefix before first underscore or dot
            patient_id = file_path.stem.split("_")[0]
            if patient_id not in patient_samples:
                patient_samples[patient_id] = []
            patient_samples[patient_id].append((file_path, class_to_idx[class_name]))

    patient_ids = list(patient_samples.keys())
    random.shuffle(patient_ids)

    n_total = len(patient_ids)
    if n_total == 0:
        logger.warning("No samples found in %s matching class structure", data_dir)
        return [], [], []

    n_train = int(n_total * train_ratio)
    n_val = int(n_total * val_ratio)

    train_patients = set(patient_ids[:n_train])
    val_patients = set(patient_ids[n_train:n_train + n_val])
    test_patients = set(patient_ids[n_train + n_val:])

    train_samples = [s for pid in train_patients for s in patient_samples[pid]]
    val_samples = [s for pid in val_patients for s in patient_samples[pid]]
    test_samples = [s for pid in test_patients for s in patient_samples[pid]]

    logger.info(
        "Patient-level split complete: %d patients (%d slices train, %d val, %d test)",
        n_total, len(train_samples), len(val_samples), len(test_samples)
    )
    return train_samples, val_samples, test_samples


def train(
    data_dir: Path,
    output_dir: Path,
    epochs: int = 30,
    batch_size: int = 32,
    lr: float = 1e-4,
    device_name: str = "cpu"
):
    device = torch.device(device_name if torch.cuda.is_available() and device_name != "cpu" else "cpu")
    logger.info("Using device: %s", device)

    pipeline = PreprocessingPipeline(target_size=(224, 224))
    train_samples, val_samples, test_samples = patient_level_split(data_dir)

    if not train_samples:
        logger.error("No training data available. Cannot proceed with training.")
        return

    train_dataset = BrainMRIDataset(train_samples, pipeline)
    val_dataset = BrainMRIDataset(val_samples, pipeline)

    train_loader = DataLoader(train_dataset, batch_size=batch_size, shuffle=True)
    val_loader = DataLoader(val_dataset, batch_size=batch_size, shuffle=False)

    model = BrainTumorCNN(num_classes=len(CLASS_NAMES)).to(device)
    criterion = nn.CrossEntropyLoss()
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-2)

    best_val_loss = float("inf")
    output_dir.mkdir(parents=True, exist_ok=True)
    best_weights_path = output_dir / "classifier_cnn_v1.pt"

    for epoch in range(1, epochs + 1):
        model.train()
        train_loss = 0.0
        train_correct = 0
        total_train = 0

        for images, labels in train_loader:
            images, labels = images.to(device), labels.to(device)
            optimizer.zero_grad()
            outputs = model(images)
            loss = criterion(outputs, labels)
            loss.backward()
            optimizer.step()

            train_loss += loss.item() * images.size(0)
            preds = outputs.argmax(dim=1)
            train_correct += (preds == labels).sum().item()
            total_train += images.size(0)

        # Validation
        model.eval()
        val_loss = 0.0
        val_correct = 0
        total_val = 0

        with torch.no_grad():
            for images, labels in val_loader:
                images, labels = images.to(device), labels.to(device)
                outputs = model(images)
                loss = criterion(outputs, labels)
                val_loss += loss.item() * images.size(0)
                preds = outputs.argmax(dim=1)
                val_correct += (preds == labels).sum().item()
                total_val += images.size(0)

        train_acc = train_correct / max(total_train, 1)
        val_acc = val_correct / max(total_val, 1)
        val_loss /= max(total_val, 1)

        logger.info(
            "Epoch %d/%d — Train Loss: %.4f, Acc: %.4f | Val Loss: %.4f, Acc: %.4f",
            epoch, epochs, train_loss / max(total_train, 1), train_acc, val_loss, val_acc
        )

        if val_loss < best_val_loss:
            best_val_loss = val_loss
            payload = {
                "version": "1.0.0",
                "model_name": "BrainTumorCNN",
                "preprocessing_version": pipeline.version,
                "classes": CLASS_NAMES,
                "state_dict": model.state_dict(),
            }
            torch.save(payload, best_weights_path)
            logger.info("Saved new best model to %s", best_weights_path)

    if best_weights_path.exists():
        with open(best_weights_path, "rb") as f:
            checksum = hashlib.sha256(f.read()).hexdigest()
        logger.info("Training complete. Checksum SHA-256: %s", checksum)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Train BrainTumorCNN on brain MRI dataset")
    parser.add_argument("--data-dir", type=Path, default=Path("datasets/classification"), help="Path to classification dataset")
    parser.add_argument("--output-dir", type=Path, default=Path("weights"), help="Path to weights output directory")
    parser.add_argument("--epochs", type=int, default=20, help="Number of training epochs")
    parser.add_argument("--batch-size", type=int, default=32, help="Batch size")
    parser.add_argument("--lr", type=float, default=1e-4, help="Learning rate")
    parser.add_argument("--device", type=str, default="cpu", help="Device (cpu or cuda)")

    args = parser.parse_args()
    train(args.data_dir, args.output_dir, args.epochs, args.batch_size, args.lr, args.device)
