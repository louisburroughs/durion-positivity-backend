package com.positivity.accounting.internal.dto;

/**
 * Thrown when a journal entry fails balance validation (total debits ≠ total
 * credits).
 */
public class UnbalancedEntryException extends IllegalArgumentException {

    public UnbalancedEntryException(String message) {
        super(message);
    }
}
