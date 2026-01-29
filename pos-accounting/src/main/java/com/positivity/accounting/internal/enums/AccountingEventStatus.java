package com.positivity.accounting.internal.enums;

/**
 * Accounting Event ingestion and processing status.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide</a>
 */
public enum AccountingEventStatus {
    /**
     * Event has been received and persisted, awaiting processing.
     */
    RECEIVED,

    /**
     * Event is currently being processed (JE generation in progress).
     */
    PROCESSING,

    /**
     * Event has been successfully processed and JE created.
     */
    PROCESSED,

    /**
     * Event processing failed (mapping not found, validation error, etc.).
     */
    FAILED,

    /**
     * Event has been suspended for manual review/resolution.
     */
    SUSPENDED
}
