package com.positivity.accounting.internal.exception;

/**
 * Thrown when a mutation (match, unmatch, adjustment, or re-finalize) is attempted on
 * a bank reconciliation that is already FINALIZED (Story F2, issue #965). Maps to 409
 * RECONCILIATION_ALREADY_FINALIZED.
 */
public class ReconciliationAlreadyFinalizedException extends RuntimeException {

    public ReconciliationAlreadyFinalizedException(String message) {
        super(message);
    }
}
