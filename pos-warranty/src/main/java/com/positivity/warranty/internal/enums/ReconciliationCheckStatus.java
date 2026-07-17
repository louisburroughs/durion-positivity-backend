package com.positivity.warranty.internal.enums;

/**
 * Outcome of checking a FAILED settlement against pos-invoice (issue #922): did the invoice
 * write actually commit despite the transport failure warranty observed (timeout-after-commit)?
 */
public enum ReconciliationCheckStatus {
    /** pos-invoice holds a committed adjustment/refund for this settlement — double-pay risk. */
    CONFIRMED_COMMITTED,
    /** pos-invoice holds no matching write — the settlement can safely be retried. */
    CONFIRMED_ABSENT,
    /** pos-invoice could not be consulted; re-run the worklist before acting. */
    UNKNOWN
}
