-- ===========================================================================
-- BrainTwinX — Seed default model versions
-- ===========================================================================

INSERT INTO model_versions (
    model_name, model_type, version, framework, checksum_sha256, input_shape,
    preprocessing_version, status, notes, created_at, updated_at, lock_version
) VALUES
('BrainTumorCNN', 'CLASSIFIER', '1.0.0', 'PyTorch', NULL, '1x3x224x224',
 '1.0.0', 'ACTIVE', 'Standard 4-class brain tumor CNN classifier (glioma, meningioma, pituitary, no_tumor)',
 UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),

('BrainTumorUNet', 'SEGMENTER', '1.0.0', 'PyTorch', NULL, '1x3x256x256',
 '1.0.0', 'ACTIVE', 'Brain tumor semantic segmentation U-Net',
 UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),

('BrainTumorLSTM', 'FORECASTER', '1.0.0', 'PyTorch', NULL, 'BxTx1',
 '1.0.0', 'ACTIVE', 'Longitudinal volumetric growth forecaster',
 UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);
