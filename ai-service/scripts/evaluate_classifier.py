"""
BrainTwinX — CNN Classifier Evaluation Harness
Calculates Accuracy, Precision, Recall, F1-Score, ROC-AUC, and Confusion Matrix
on an unseen patient-level test holdout.
"""

import argparse
import json
import logging
from pathlib import Path
import sys
from typing import Dict, List, Tuple

import numpy as np
import torch
from torch.utils.data import DataLoader

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.models.classifier import BrainTumorCNN, CLASS_NAMES, load_classifier_weights
from app.preprocessing.pipeline import PreprocessingPipeline
from scripts.train_classifier import BrainMRIDataset, patient_level_split

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("evaluate_classifier")


def compute_metrics(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    y_prob: np.ndarray,
    class_names: List[str]
) -> Dict:
    """
    Computes confusion matrix, per-class and macro-averaged precision, recall, F1,
    overall accuracy, and multi-class one-vs-rest ROC-AUC.
    """
    num_classes = len(class_names)
    cm = np.zeros((num_classes, num_classes), dtype=int)
    for t, p in zip(y_true, y_pred):
        cm[t, p] += 1

    accuracy = float(np.trace(cm) / max(len(y_true), 1))

    per_class = {}
    macro_precision, macro_recall, macro_f1 = 0.0, 0.0, 0.0

    for i, name in enumerate(class_names):
        tp = cm[i, i]
        fp = cm[:, i].sum() - tp
        fn = cm[i, :].sum() - tp

        precision = float(tp / (tp + fp)) if (tp + fp) > 0 else 0.0
        recall = float(tp / (tp + fn)) if (tp + fn) > 0 else 0.0
        f1 = float(2 * precision * recall / (precision + recall)) if (precision + recall) > 0 else 0.0

        per_class[name] = {
            "precision": round(precision, 4),
            "recall": round(recall, 4),
            "f1_score": round(f1, 4),
            "support": int(cm[i, :].sum()),
        }
        macro_precision += precision
        macro_recall += recall
        macro_f1 += f1

    macro_precision /= num_classes
    macro_recall /= num_classes
    macro_f1 /= num_classes

    # Simple multiclass ROC-AUC approximation (one-vs-rest)
    roc_auc_scores = {}
    for i, name in enumerate(class_names):
        binary_true = (y_true == i).astype(int)
        binary_prob = y_prob[:, i]
        if binary_true.sum() > 0 and (1 - binary_true).sum() > 0:
            # Rank-order calculation for Wilcoxon-Mann-Whitney AUC
            order = np.argsort(binary_prob)
            ranks = np.empty_like(order)
            ranks[order] = np.arange(len(binary_prob))
            pos_ranks = ranks[binary_true == 1].sum()
            n_pos = binary_true.sum()
            n_neg = len(binary_true) - n_pos
            auc = float((pos_ranks - n_pos * (n_pos - 1) / 2) / (n_pos * n_neg))
            roc_auc_scores[name] = round(auc, 4)
        else:
            roc_auc_scores[name] = None

    valid_aucs = [v for v in roc_auc_scores.values() if v is not None]
    macro_auc = round(float(np.mean(valid_aucs)), 4) if valid_aucs else None

    return {
        "accuracy": round(accuracy, 4),
        "macro_precision": round(macro_precision, 4),
        "macro_recall": round(macro_recall, 4),
        "macro_f1": round(macro_f1, 4),
        "macro_roc_auc": macro_auc,
        "per_class": per_class,
        "roc_auc_per_class": roc_auc_scores,
        "confusion_matrix": cm.tolist(),
        "classes": class_names,
    }


def evaluate(data_dir: Path, weights_path: Path, output_file: Path = None):
    pipeline = PreprocessingPipeline(target_size=(224, 224))
    _, _, test_samples = patient_level_split(data_dir)

    if not test_samples:
        logger.error("No test samples available for evaluation in %s", data_dir)
        return

    logger.info("Evaluating on %d test slices...", len(test_samples))

    if not weights_path.exists():
        logger.error("Weights file %s not found. Evaluation cannot run without model weights.", weights_path)
        return

    model, meta = load_classifier_weights(weights_path)
    model.eval()

    test_dataset = BrainMRIDataset(test_samples, pipeline)
    test_loader = DataLoader(test_dataset, batch_size=32, shuffle=False)

    all_preds = []
    all_targets = []
    all_probs = []

    with torch.no_grad():
        for images, labels in test_loader:
            outputs = model(images)
            probs = torch.softmax(outputs, dim=1).numpy()
            preds = outputs.argmax(dim=1).numpy()

            all_probs.append(probs)
            all_preds.extend(preds)
            all_targets.extend(labels.numpy())

    y_true = np.array(all_targets)
    y_pred = np.array(all_preds)
    y_prob = np.concatenate(all_probs, axis=0)

    metrics = compute_metrics(y_true, y_pred, y_prob, CLASS_NAMES)

    logger.info("=== EVALUATION REPORT ===")
    logger.info("Overall Accuracy: %.4f", metrics["accuracy"])
    logger.info("Macro F1: %.4f | Macro Precision: %.4f | Macro Recall: %.4f",
                metrics["macro_f1"], metrics["macro_precision"], metrics["macro_recall"])
    if metrics["macro_roc_auc"]:
        logger.info("Macro ROC-AUC: %.4f", metrics["macro_roc_auc"])

    for cls, res in metrics["per_class"].items():
        logger.info("  Class %-12s: P=%.4f R=%.4f F1=%.4f (Support: %d)",
                    cls, res["precision"], res["recall"], res["f1_score"], res["support"])

    if output_file:
        output_file.parent.mkdir(parents=True, exist_ok=True)
        with open(output_file, "w") as f:
            json.dump(metrics, f, indent=2)
        logger.info("Metrics written to %s", output_file)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Evaluate BrainTumorCNN classifier")
    parser.add_argument("--data-dir", type=Path, default=Path("datasets/classification"), help="Dataset directory")
    parser.add_argument("--weights", type=Path, default=Path("weights/classifier_cnn_v1.pt"), help="Model weights path")
    parser.add_argument("--output", type=Path, default=Path("eval_results.json"), help="Output metrics JSON")

    args = parser.parse_args()
    evaluate(args.data_dir, args.weights, args.output)
