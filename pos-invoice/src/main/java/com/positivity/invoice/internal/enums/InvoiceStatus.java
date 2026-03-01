package com.positivity.invoice.internal.enums;

/**
 * Lifecycle status for invoices.
 */
public enum InvoiceStatus {
    DRAFT,
    FINALIZED,
    /** Invoice has been posted to the general ledger (accounting). */
    POSTED,
    /** Invoice finalization or GL posting encountered an error. */
    ERROR
}
