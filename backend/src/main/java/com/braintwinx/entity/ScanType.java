package com.braintwinx.entity;

/**
 * MRI acquisition sequence.
 *
 * <p>Recorded for traceability. A model trained on one sequence should not be applied
 * blindly to another; keeping the sequence on the scan record is what makes such a
 * mismatch detectable rather than invisible.
 */
public enum ScanType {

    /** T1-weighted. */
    MRI_T1,

    /** T1-weighted with contrast enhancement. */
    MRI_T1C,

    /** T2-weighted. */
    MRI_T2,

    /** Fluid-attenuated inversion recovery. */
    MRI_FLAIR,

    /** Sequence not among the above, or not supplied. */
    OTHER
}
