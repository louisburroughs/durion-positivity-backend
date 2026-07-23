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
    ERROR,
    /**
     * Terminal: draft cancelled before any money moved (order-void path, parity story C5). Only
     * reachable from DRAFT with no authorized/captured payments.
     */
    CANCELLED
}
