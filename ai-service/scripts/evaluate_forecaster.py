"""Offline evaluation harness for LSTM tumor growth forecaster (Phase 9).

Metrics computed:
- Mean Absolute Error (MAE) in pixels
- Root Mean Squared Error (RMSE) in pixels
- Mean Absolute Percentage Error (MAPE)
- Trend Classification Accuracy

Per brief §13 and §46:
These evaluation metrics belong exclusively to this offline evaluation harness.
They are never attached to live clinical inferences.
"""

from __future__ import annotations

import argparse
import math
from typing import Dict, List, Tuple
import torch
import torch.nn as nn

from app.models.forecaster import TumorGrowthLSTM, load_forecaster_weights


def compute_forecaster_metrics(
    predictions: List[float],
    ground_truth: List[float],
    predicted_trends: List[str],
    actual_trends: List[str],
) -> Dict[str, float]:
    """Computes standard time-series regression and classification metrics.

    Args:
        predictions: Predicted area values (pixels)
        ground_truth: Actual observed area values (pixels)
        predicted_trends: Predicted trend categories
        actual_trends: Actual trend categories

    Returns:
        Dict with mae, rmse, mape, and trend_accuracy
    """
    if len(predictions) != len(ground_truth) or len(predictions) == 0:
        raise ValueError("Predictions and ground truth must be non-empty and of equal length.")

    n = len(predictions)
    abs_errors = [abs(p - g) for p, g in zip(predictions, ground_truth)]
    sq_errors = [(p - g) ** 2 for p, g in zip(predictions, ground_truth)]

    mae = sum(abs_errors) / n
    rmse = math.sqrt(sum(sq_errors) / n)

    # MAPE: avoid division by zero
    pct_errors = [
        abs(p - g) / max(1.0, g)
        for p, g in zip(predictions, ground_truth)
    ]
    mape = (sum(pct_errors) / n) * 100.0

    trend_correct = sum(1 for pt, at in zip(predicted_trends, actual_trends) if pt == at)
    trend_accuracy = trend_correct / n if n > 0 else 0.0

    return {
        "mae_px": round(mae, 2),
        "rmse_px": round(rmse, 2),
        "mape_pct": round(mape, 2),
        "trend_accuracy": round(trend_accuracy, 4),
        "sample_count": n,
    }


def main():
    parser = argparse.ArgumentParser(description="Evaluate LSTM tumor growth forecaster")
    parser.add_argument("--weights", type=str, required=True, help="Path to model weights (.pt)")
    parser.add_argument("--checksum", type=str, default=None, help="Expected SHA-256")
    args = parser.parse_args()

    model = load_forecaster_weights(args.weights, expected_sha256=args.checksum)
    print(f"Loaded forecaster model: {model}")
    print("Run evaluation against longitudinal test cohorts.")


if __name__ == "__main__":
    main()
