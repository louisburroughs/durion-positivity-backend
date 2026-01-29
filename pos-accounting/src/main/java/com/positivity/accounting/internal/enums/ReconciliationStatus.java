package com.positivity.accounting.internal.enums;

/**
 * Reconciliation Status enum.
 * 
 * Lifecycle: IN_PROGRESS → FINALIZED (or CANCELLED)
 */
public enum ReconciliationStatus {
    IN_PROGRESS,
    FINALIZED,
    CANCELLED
}
