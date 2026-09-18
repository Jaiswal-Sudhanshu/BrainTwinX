# BrainTwinX — Dataset Setup & Training Guide

This document outlines the dataset preparation, directory layout, patient-level splitting guarantees, and instructions for running the classifier training script and evaluation harness.

---

## 1. Supported Datasets

BrainTwinX supports standard brain MRI datasets for multi-class tumor classification into 4 categories:
- `glioma`
- `meningioma`
- `pituitary`
- `no_tumor`

Common public sources:
- **Figshare Brain Tumor Dataset** (Cheng et al.): 3,064 T1-weighted CE-MRI slices across 233 patients.
- **Sartaj Bhavsar / Kaggle Brain Tumor Classification (MRI)**: Multi-sequence collection formatted across the 4 standard classes.

---

## 2. Directory Layout & Patient Naming Convention

To guarantee zero patient data leakage across training, validation, and test splits, slice filenames **must** include the patient identifier as a prefix:

```
ai-service/datasets/classification/
├── glioma/
│   ├── pat001_slice01.png
│   ├── pat001_slice02.png
│   ├── pat002_slice01.png
│   └── ...
├── meningioma/
│   ├── pat050_slice01.png
│   ├── pat050_slice02.png
│   └── ...
├── pituitary/
│   ├── pat120_slice01.png
│   └── ...
└── no_tumor/
    ├── pat200_slice01.png
    └── ...
```

### The Patient-Level Split Invariant
Adjacent slices from the same 3D MRI volume share anatomical context and tissue features. Splitting randomly at the slice level leaks patient-specific features into validation and test sets, artificially inflating reported performance. 

The BrainTwinX split utility (`patient_level_split`) groups all slices by patient prefix (`pat<id>`) before partitioning:
- **Train Split (70%)**: All slices from 70% of patients.
- **Validation Split (15%)**: All slices from 15% of patients (used for early stopping).
- **Test Holdout (15%)**: All slices from 15% of patients (evaluated once post-training).

---

## 3. Running Training

From `ai-service/`:

```powershell
.\.venv\Scripts\python scripts/train_classifier.py --data-dir datasets/classification --epochs 30 --batch-size 32 --lr 0.0001
```

Options:
- `--data-dir`: Directory containing class folders.
- `--output-dir`: Output folder for trained `.pt` weight files (defaults to `weights/`).
- `--epochs`: Number of training epochs (default: `20`).
- `--batch-size`: Mini-batch size (default: `32`).
- `--device`: `cpu` or `cuda`.

When complete, the best checkpoint is written to:
`weights/classifier_cnn_v1.pt`

The script computes and logs the SHA-256 checksum of the exported weights.

---

## 4. Running the Evaluation Harness

To produce accuracy, precision, recall, F1-scores, ROC-AUC, and confusion matrix on the unseen patient test holdout:

```powershell
.\.venv\Scripts\python scripts/evaluate_classifier.py --data-dir datasets/classification --weights weights/classifier_cnn_v1.pt --output eval_results.json
```

Output:
- Formatted console report of per-class precision, recall, and F1.
- Macro-averaged F1 and ROC-AUC.
- Full confusion matrix.
- Structured JSON output written to `eval_results.json`.

---

## 5. Model Registry Registration

Once trained weights are validated, register the SHA-256 checksum in the MySQL `model_versions` table:

```sql
UPDATE model_versions 
SET checksum_sha256 = '<sha256_hexdigest>',
    status = 'ACTIVE',
    updated_at = UTC_TIMESTAMP(6)
WHERE model_name = 'BrainTumorCNN' AND version = '1.0.0';
```

When the AI service starts, it validates the physical file checksum against `checksum_sha256`. Any discrepancy causes `load_classifier_weights` to abort and the service to fail closed (503 Service Unavailable), guaranteeing that unverified or corrupted weights are never served in clinical or research environments.
