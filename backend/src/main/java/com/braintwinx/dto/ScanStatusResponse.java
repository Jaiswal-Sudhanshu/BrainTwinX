package com.braintwinx.dto;

import com.braintwinx.entity.JobStatus;
import com.braintwinx.entity.ScanStatus;

/**
 * Public representation of scan processing status and active job progress.
 */
public record ScanStatusResponse(
        String scanPublicId,
        ScanStatus scanStatus,
        String latestJobPublicId,
        JobStatus jobStatus,
        short progressPercent,
        String failureCode,
        String failureReason
) {}
