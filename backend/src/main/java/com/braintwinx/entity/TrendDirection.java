package com.braintwinx.entity;

/**
 * Direction of an observed longitudinal trend.
 *
 * <p>Deliberately coarse. A model-based estimate over a handful of observations does
 * not support a precise growth-rate claim, and presenting one would overstate what the
 * data can show (brief section 44).
 */
public enum TrendDirection {

    /** Measurements increasing over the observed window. */
    INCREASING,

    /** Measurements decreasing over the observed window. */
    DECREASING,

    /** No material change over the observed window. */
    STABLE,

    /** History was sufficient to analyse but yielded no clear direction. */
    INDETERMINATE
}
