# Datasets

**No dataset is present in this repository, and none is committed.**

Dataset files are excluded by `.gitignore`. This directory holds only this README and a
`.gitkeep` placeholder.

## Why datasets are not committed

1. **Licensing.** Brain MRI datasets carry licence terms that generally prohibit
   redistribution. Committing one would breach those terms.
2. **Privacy.** Medical imaging may contain protected health information. Even
   nominally de-identified imaging can carry PHI in embedded metadata.
3. **Size.** Imaging datasets are far too large for version control.
4. **Integrity.** Per the project brief (§24), arbitrary downloaded data must never be
   presented as production-quality training material.

## Current state

There is **no dataset available for this project**, confirmed with the project owner on
2026-08-22. Consequently:

- no models have been trained
- no accuracy, Dice, IoU, or error metrics are reported anywhere
- the AI service reports **NOT READY** while weights are absent
- analysis endpoints return a typed error rather than any fabricated prediction

This is recorded as assumption **A-1** in [`../../docs/ASSUMPTIONS.md`](../../docs/ASSUMPTIONS.md)
and as blockers **B-1** through **B-4** in [`../../docs/TASKS.md`](../../docs/TASKS.md).

## Adding a dataset

Full instructions, including the expected directory layout, class mapping, and the
patient-level splitting requirement that prevents leakage between train and test sets,
are documented in `docs/DATASET_SETUP.md` *(authored in Phase 7)*.

Whatever dataset is supplied, the following must be recorded before training:

- source and citation
- licence and any redistribution restrictions
- class names and label semantics
- sample count and class distribution
- image format and resolution
- train / validation / test split strategy — **split by patient, never by image**
- preprocessing version applied
- augmentation applied

These are prerequisites for `docs/AI_EVALUATION.md`, which must never contain a metric
that was not produced by an actual evaluation run.
