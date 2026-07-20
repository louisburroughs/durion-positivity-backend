package com.positivity.accounting.internal.exception;

/**
 * Thrown when a settlement line write-off exceeds the configured self-service
 * threshold (story F1c, issue #963, decision D-14). Maps to 422
 * WRITE_OFF_THRESHOLD_EXCEEDED.
 */
public class SettlementWriteOffThresholdExceededException extends RuntimeException {

    public SettlementWriteOffThresholdExceededException(String message) {
        super(message);
    }
}
