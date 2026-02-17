package com.positivity.inventory.internal.enums;

/**
 * Reason codes for inventory reconciliation actions.
 * Used when physical inventory does not match system records.
 */
public enum ReconciliationReasonCode {
    /**
     * Stock was misplaced or found in unexpected location.
     */
    MISPLACED_STOCK,

    /**
     * Receipt was not properly recorded in system.
     */
    UNRECORDED_RECEIPT,

    /**
     * Inventory loss or theft detected.
     */
    SHRINKAGE,

    /**
     * Cycle count revealed discrepancy.
     */
    CYCLE_COUNT_ADJUSTMENT,

    /**
     * System error or data corruption.
     */
    SYSTEM_ERROR,

    /**
     * Other reason with detailed explanation.
     */
    OTHER
}
