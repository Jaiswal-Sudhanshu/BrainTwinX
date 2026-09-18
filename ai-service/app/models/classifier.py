import hashlib
from pathlib import Path
from typing import Dict, List, Optional, Tuple
import torch
import torch.nn as nn
import torch.nn.functional as F

CLASS_NAMES: List[str] = ["glioma", "meningioma", "pituitary", "no_tumor"]


class BrainTumorCNN(nn.Module):
    """
    CNN architecture for 4-class brain tumor classification from MRI slices.
    Input shape: (batch_size, 1, 224, 224)
    Output shape: (batch_size, 4)
    """

    def __init__(self, num_classes: int = 4):
        super().__init__()
        self.num_classes = num_classes

        self.features = nn.Sequential(
            # Block 1
            nn.Conv2d(1, 32, kernel_size=3, padding=1, bias=False),
            nn.BatchNorm2d(32),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=2, stride=2),  # -> 112x112

            # Block 2
            nn.Conv2d(32, 64, kernel_size=3, padding=1, bias=False),
            nn.BatchNorm2d(64),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=2, stride=2),  # -> 56x56

            # Block 3
            nn.Conv2d(64, 128, kernel_size=3, padding=1, bias=False),
            nn.BatchNorm2d(128),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=2, stride=2),  # -> 28x28

            # Block 4
            nn.Conv2d(128, 256, kernel_size=3, padding=1, bias=False),
            nn.BatchNorm2d(256),
            nn.ReLU(inplace=True),
            nn.AdaptiveAvgPool2d((7, 7)),  # -> 256x7x7
        )

        self.classifier = nn.Sequential(
            nn.Dropout(p=0.4),
            nn.Linear(256 * 7 * 7, 512),
            nn.ReLU(inplace=True),
            nn.Dropout(p=0.3),
            nn.Linear(512, num_classes)
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        if x.dim() != 4 or x.size(1) != 1 or x.size(2) != 224 or x.size(3) != 224:
            raise ValueError(f"Expected input shape (B, 1, 224, 224), got {tuple(x.shape)}")
        feats = self.features(x)
        flattened = torch.flatten(feats, 1)
        logits = self.classifier(flattened)
        return logits

    @torch.no_grad()
    def predict_probabilities(self, x: torch.Tensor) -> Tuple[str, float, Dict[str, float]]:
        """
        Runs inference and returns:
        1. Top predicted class name
        2. Top-1 confidence in [0.0, 1.0]
        3. Full dictionary of class -> probability (summing to 1.0)
        """
        self.eval()
        logits = self.forward(x)
        probs = F.softmax(logits, dim=1).squeeze(0)  # Shape: (4,)

        prob_dict: Dict[str, float] = {}
        for idx, class_name in enumerate(CLASS_NAMES):
            prob_dict[class_name] = float(probs[idx].item())

        top_prob, top_idx = torch.max(probs, dim=0)
        predicted_class = CLASS_NAMES[top_idx.item()]
        confidence = float(top_prob.item())

        return predicted_class, confidence, prob_dict


def compute_file_sha256(file_path: Path) -> str:
    """Computes SHA-256 digest of a weight file."""
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def load_classifier_weights(
    weights_path: Path,
    expected_sha256: Optional[str] = None
) -> BrainTumorCNN:
    """
    Loads BrainTumorCNN weights with checksum and shape validation.
    """
    if not weights_path.exists():
        raise FileNotFoundError(f"Model weights file not found: {weights_path}")

    if expected_sha256 is not None:
        actual_sha = compute_file_sha256(weights_path)
        if actual_sha.lower() != expected_sha256.lower():
            raise ValueError(
                f"Model weights checksum mismatch! Expected: {expected_sha256}, Actual: {actual_sha}"
            )

    model = BrainTumorCNN(num_classes=len(CLASS_NAMES))
    checkpoint = torch.load(weights_path, map_location="cpu", weights_only=True)

    state_dict = checkpoint.get("state_dict", checkpoint) if isinstance(checkpoint, dict) else checkpoint
    model.load_state_dict(state_dict)
    model.eval()
    return model
