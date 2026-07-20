package com.positivity.accounting.internal.exception;

/**
 * Thrown when a processor settlement line cannot be resolved by id (story F1c,
 * issue #963). Maps to 404 SETTLEMENT_LINE_NOT_FOUND.
 */
public class SettlementLineNotFoundException extends RuntimeException {

    public SettlementLineNotFoundException(String message) {
        super(message);
    }
}
