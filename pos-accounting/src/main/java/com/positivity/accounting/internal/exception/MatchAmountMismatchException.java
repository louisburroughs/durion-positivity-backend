package com.positivity.accounting.internal.exception;

/**
 * Thrown when the statement-line set and GL-line set of a match do not net to equal
 * signed amounts within ±0.01 (Story F2, issue #965). Maps to 422
 * MATCH_AMOUNT_MISMATCH.
 */
public class MatchAmountMismatchException extends RuntimeException {

    public MatchAmountMismatchException(String message) {
        super(message);
    }
}
