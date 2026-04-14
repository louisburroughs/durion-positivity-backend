package com.positivity.accounting.internal.enums;

/**
 * Reason codes for manual journal entries.
 *
 * Required for all MANUAL journal entries to provide audit trail and
 * justification.
 *
 * @see com.positivity.accounting.entity.JournalEntry
 */
public enum ManualJEReasonCode {
    /**
     * Period-end accrual adjustment (e.g., prepaid expenses, deferred revenue).
     */
    ACCRUAL_ADJUSTMENT,

    /**
     * Correcting a posting error or misclassification.
     */
    ERROR_CORRECTION,

    /**
     * Reclassifying amounts between accounts for reporting purposes.
     */
    RECLASSIFICATION,

    /**
     * Recording depreciation or amortization expense.
     */
    DEPRECIATION,

    /**
     * Other reason (requires detailed justification).
     */
    OTHER
}
