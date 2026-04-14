package com.positivity.accounting.internal.enums;

/**
 * Enum for journal entry type classification.
 *
 * Used to distinguish event-driven postings from manual adjustments.
 *
 * @see com.positivity.accounting.entity.JournalEntry
 */
public enum JournalEntryType {
    /**
     * Generated from business events (e.g., WorkCompleted, InvoicePosted).
     */
    EVENT_DRIVEN,

    /**
     * Created manually by accounting staff (requires reason code and
     * justification).
     */
    MANUAL
}
