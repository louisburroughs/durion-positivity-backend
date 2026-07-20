package com.positivity.accounting.internal.exception;

/**
 * Thrown when a bank reconciliation (or one of its lines) is not found (Story F2,
 * issue #965). Maps to 404 RECONCILIATION_NOT_FOUND.
 */
public class ReconciliationNotFoundException extends RuntimeException {

    public ReconciliationNotFoundException(String message) {
        super(message);
    }
}
