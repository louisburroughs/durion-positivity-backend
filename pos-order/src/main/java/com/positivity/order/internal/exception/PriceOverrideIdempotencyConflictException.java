package com.positivity.order.internal.exception;

/**
 * Raised when an idempotency key is reused with a different payload.
 */
public class PriceOverrideIdempotencyConflictException extends RuntimeException {

    public PriceOverrideIdempotencyConflictException(String message) {
        super(message);
    }
}
