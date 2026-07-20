package com.positivity.accounting.internal.exception;

/**
 * Thrown when a manual match or write-off targets a settlement line that is no
 * longer UNMATCHED (story F1c, issue #963). Maps to 409
 * SETTLEMENT_LINE_NOT_UNMATCHED.
 */
public class SettlementLineNotUnmatchedException extends RuntimeException {

    public SettlementLineNotUnmatchedException(String message) {
        super(message);
    }
}
