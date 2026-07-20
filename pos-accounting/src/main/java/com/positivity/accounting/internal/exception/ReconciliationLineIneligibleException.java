package com.positivity.accounting.internal.exception;

/**
 * Thrown when a line referenced by a match is ineligible (Story F2, issue #965): a
 * statement line that is not UNMATCHED, a GL line that is not on a POSTED entry, or a
 * GL line already reconciled in this or another reconciliation. Maps to 409
 * RECONCILIATION_LINE_INELIGIBLE so ADR-0017 clients can distinguish a state conflict
 * from a malformed request.
 */
public class ReconciliationLineIneligibleException extends RuntimeException {

    public ReconciliationLineIneligibleException(String message) {
        super(message);
    }
}
