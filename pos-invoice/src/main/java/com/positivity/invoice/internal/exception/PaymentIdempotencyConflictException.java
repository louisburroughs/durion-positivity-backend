package com.positivity.invoice.internal.exception;

/**
 * Thrown when an idempotency key is reused with a different payment payload.
 */
public class PaymentIdempotencyConflictException extends RuntimeException {

    public PaymentIdempotencyConflictException(String message) {
        super(message);
    }
}
