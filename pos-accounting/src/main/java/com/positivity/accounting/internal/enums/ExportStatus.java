package com.positivity.accounting.internal.enums;

/**
 * Lifecycle status of an asynchronous report export job.
 */
public enum ExportStatus {
    /** Export has been queued but not yet started. */
    PENDING,

    /** Export is actively being processed. */
    IN_PROGRESS,

    /** Export completed successfully; download URL is available. */
    COMPLETED,

    /** Export failed; no download URL available. */
    FAILED
}
