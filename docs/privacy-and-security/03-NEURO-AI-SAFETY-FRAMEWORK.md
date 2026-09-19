# BrainTwinX — Neuro-AI Clinical Safety Framework

**Classification:** Clinical AI Safety Architecture & SaMD Guidance  
**Standards:** FDA Guidance on Clinical Decision Support Software (CDS), IMDRF SaMD Framework, EU AI Act (High-Risk AI Systems — Annex III)

---

## 1. Clinical Context & SaMD Categorization

BrainTwinX provides clinical decision support (CDS) for healthcare professionals evaluating brain magnetic resonance imaging (MRI). The platform is engineered according to the FDA Section 520(o)(1)(E) criteria for non-device clinical decision support:

1. **Not intended to replace clinical judgment:** The system provides decision-support outputs; it does not issue automated clinical diagnoses or direct patient management.
2. **Transparent inputs and provenance:** Every finding displays the originating model identity, model version, preprocessing version, confidence distribution, and timestamp.
3. **Auditable basis of recommendations:** The healthcare professional can review the segmented mask bounding box and probability distribution independently to verify the AI recommendation.

---

## 2. The Core Safety Invariants

### 1. Zero Invented Predictions & Fail-Closed Integrity
- When model weights are absent or corrupt, the system **must fail closed** (HTTP 503 `AI_SERVICE_UNAVAILABLE`).
- It is strictly forbidden to emit a random or default prediction in production when a model fails.
- In development/testing, any stub-derived output is explicitly tagged `isSynthetic=true` in the database and API response, preventing it from ever being misinterpreted as clinical findings.

### 2. Exclusion of Ground-Truth Metrics from Clinical Inferences
- Metrics such as the **Sørensen-Dice Coefficient** and **Intersection-over-Union (IoU)** measure mathematical overlap between a prediction and an expert ground-truth manual segmentation.
- On a live clinical scan, **no ground truth exists**.
- Reporting a "Dice score of 0.91" on a live patient scan is physically impossible and constitutes a fabricated clinical metric.
- **Architectural Countermeasure:** The database schema (`segmentation_results`), backend DTOs, and API responses have **zero fields** for Dice or IoU. Those metrics exist exclusively in the offline scientific evaluation harness (`evaluate_segmenter.py`).

### 3. Explicit Units for Tumour Area
- 2D PNG/JPEG MRI slices do not contain calibrated DICOM pixel spacing metadata ($x, y$ in mm/pixel) or slice thickness ($z$ in mm).
- Calculating physical volume ($cm^3$) or physical area ($mm^2$) without pixel spacing constitutes fabricated measurement.
- **Architectural Countermeasure:** Area is reported explicitly as `tumorAreaPx` (pixels of the $224 \times 224$ preprocessed image slice), with the preprocessing version documented.

### 4. Non-Extrapolation on Longitudinal History
- Brain tumor growth dynamics (e.g. WHO Grade II–IV diffuse astrocytomas, glioblastomas) exhibit complex non-linear Gompertzian or logistic volumetric curves.
- Attempting to extrapolate future trajectory from 1 or 2 timepoints is clinically dangerous and mathematically invalid.
- **Architectural Countermeasure:** The system requires a minimum of **3 valid observations** spanning at least **30 days**. Below this threshold, the system records `INSUFFICIENT_HISTORY` and emits **zero forecast and zero trend direction**, backed by MySQL CHECK constraint `ck_growth_insufficient_history_has_no_forecast`.

---

## 3. The 10 Clinical Safety Prohibitions for Natural Language Explanations

Large Language Models (LLMs) are prone to hallucinations, confabulations, and overconfident clinical claims. To prevent patient harm, the BrainTwinX explanation layer enforces ten strict clinical prohibitions:

```
[Structured Model Output Only]
             │
             ▼
     LLM Prompt Synthesis
(Encodes 10 Safety Prohibitions)
             │
             ▼
      Generated Draft
             │
             ▼
  [ExplanationValidator]
  ├── Check 1: Invented tumor classes?
  ├── Check 2: Invented measurements (mm², cm³)?
  ├── Check 3: Invented clinical symptoms?
  ├── Check 4: Invented medical history?
  ├── Check 5: Certainty claims (definitive, 100%)?
  ├── Check 6: Unsolicited medical prescriptions?
  ├── Check 7: Claiming to inspect MRI pixels?
  ├── Check 8: Extrapolation on INSUFFICIENT_HISTORY?
  ├── Check 9: Clinician impersonation ("I diagnose")?
  └── Check 10: Mandatory decision-support disclaimer?
             │
      ┌──────┴──────┐
   PASS           FAIL
      │             │
      ▼             ▼
  INCLUDED      REJECTED
(Persisted)   (Discarded)
```

| # | Prohibition | Clinical Rationale | Automated Validator Enforcement |
|---|---|---|---|
| **1** | **No Invented Findings** | The LLM cannot report a lesion or tumor class not detected by the CNN classifier. | Fails if mentions tumor classes other than `predictedClass`. |
| **2** | **No Invented Measurements** | The LLM cannot invent physical dimensions ($mm^2$, $cm^3$) not in the input payload. | Rejects strings matching `mm²`, `cm³`, `volume`, or numbers absent from payload. |
| **3** | **No Invented Symptoms** | The LLM has no access to patient symptoms; inventing them may bias clinical judgment. | Lexical filter rejects headache, seizure, nausea, deficit, papilledema, etc. |
| **4** | **No Invented History** | Prior surgeries, chemo, or radiation cannot be fabricated. | Rejects surgery, resection, chemotherapy, radiation, smoker, biopsy claims. |
| **5** | **No Certainty Claims** | AI models are probabilistic decision aids, never definitive truths. | Rejects "definitive", "conclusive", "guaranteed", "proven", "100%", "without doubt". |
| **6** | **No Prescriptions** | AI decision support must not prescribe medications or surgical plans. | Rejects drug names (temozolomide, dexamethasone), dosages, surgical directives. |
| **7** | **No Visual Inspection Claims** | The LLM receives structured JSON, not the image; claiming to see the image is a lie. | Rejects "looking at the image", "visible white spot", "the pixels show", "hyperintense". |
| **8** | **No Trend Extrapolation** | If status is `INSUFFICIENT_HISTORY`, no trend may be asserted. | If status is `INSUFFICIENT_HISTORY`, rejects any mention of growth or decline. |
| **9** | **No Impersonation** | The AI must never pretend to be a licensed doctor. | Rejects "I diagnose", "my medical opinion", "as a physician", "as a doctor". |
| **10** | **Mandatory Qualification** | Every report must state it is an AI decision-support estimate. | Asserts presence of approved qualification disclaimer keywords. |

---

## 4. Multimodal Isolation: Why the LLM Never Receives the Image

Modern Vision-Language Models (VLMs) frequently hallucinate fine radiologic details (e.g. claiming micro-hemorrhages or leptomeningeal enhancement based on subtle JPEG artifacts).

BrainTwinX enforces **multimodal isolation** (`ASSUMPTIONS.md` A-4):
- Specialized PyTorch CNNs and U-Nets extract validated features (class, area, bounding box).
- Only strongly typed, allow-listed structured JSON is supplied to the explanation LLM.
- **The raw MRI bytes never touch the LLM API.**
- This architectural separation completely eliminates prompt-injection attacks through steganography or image metadata and guarantees that the explanation is mathematically bounded by verified classifier telemetry.
