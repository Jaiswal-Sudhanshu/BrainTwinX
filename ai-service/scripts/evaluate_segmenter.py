"""
Offline evaluation harness for Brain MRI U-Net segmentation.
Computes Dice coefficient, IoU, Sensitivity, and Specificity against ground truth masks.

CRITICAL ARCHITECTURAL NOTE:
These metrics require ground truth masks and belong EXCLUSIVELY to this offline
evaluation harness. They must NEVER be included in production inference responses (brief §12).
"""

from typing import Dict, Tuple
import numpy as np
import torch


def compute_dice_coefficient(pred_mask: np.ndarray, gt_mask: np.ndarray, smooth: float = 1e-6) -> float:
    """
    Computes Sørensen-Dice coefficient:
    Dice = 2 * |A ∩ B| / (|A| + |B|)
    """
    pred_bin = (pred_mask > 0).astype(np.float32)
    gt_bin = (gt_mask > 0).astype(np.float32)

    intersection = np.sum(pred_bin * gt_bin)
    cardinality = np.sum(pred_bin) + np.sum(gt_bin)

    if cardinality == 0:
        return 1.0 if intersection == 0 else 0.0

    return float((2.0 * intersection + smooth) / (cardinality + smooth))


def compute_iou(pred_mask: np.ndarray, gt_mask: np.ndarray, smooth: float = 1e-6) -> float:
    """
    Computes Intersection over Union (Jaccard Index):
    IoU = |A ∩ B| / |A ∪ B|
    """
    pred_bin = (pred_mask > 0).astype(np.float32)
    gt_bin = (gt_mask > 0).astype(np.float32)

    intersection = np.sum(pred_bin * gt_bin)
    union = np.sum(np.maximum(pred_bin, gt_bin))

    if union == 0:
        return 1.0 if intersection == 0 else 0.0

    return float((intersection + smooth) / (union + smooth))


def compute_segmentation_metrics(pred_mask: np.ndarray, gt_mask: np.ndarray) -> Dict[str, float]:
    """
    Evaluates binary segmentation mask against ground truth.
    """
    pred_bin = (pred_mask > 0).astype(bool)
    gt_bin = (gt_mask > 0).astype(bool)

    tp = np.logical_and(pred_bin, gt_bin).sum()
    fp = np.logical_and(pred_bin, ~gt_bin).sum()
    fn = np.logical_and(~pred_bin, gt_bin).sum()
    tn = np.logical_and(~pred_bin, ~gt_bin).sum()

    dice = compute_dice_coefficient(pred_mask, gt_mask)
    iou = compute_iou(pred_mask, gt_mask)
    sensitivity = float(tp / (tp + fn)) if (tp + fn) > 0 else 1.0
    specificity = float(tn / (tn + fp)) if (tn + fp) > 0 else 1.0

    return {
        "dice": dice,
        "iou": iou,
        "sensitivity": sensitivity,
        "specificity": specificity,
        "tp": int(tp),
        "fp": int(fp),
        "fn": int(fn),
        "tn": int(tn)
    }


if __name__ == "__main__":
    print("BrainTwinX — Offline U-Net Evaluation Harness")
    print("This script computes validation metrics (Dice, IoU) on labeled ground-truth datasets.")
    print("See docs/DATASET_SETUP.md for preparing BraTS paired images and masks.")
