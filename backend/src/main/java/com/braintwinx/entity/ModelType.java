package com.braintwinx.entity;

/** Category of registered model (brief section 53). */
public enum ModelType {

    /** Tumour classification (CNN). */
    CLASSIFIER,

    /** Tumour segmentation (U-Net). */
    SEGMENTER,

    /** Longitudinal trend estimation (LSTM). */
    FORECASTER
}
