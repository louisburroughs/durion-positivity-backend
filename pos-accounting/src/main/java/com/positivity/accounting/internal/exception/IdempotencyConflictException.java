package com.positivity.accounting.internal.exception;

/**
 * Exception thrown when an idempotency conflict is detected.
 *
 * This occurs when a request with the same idempotency key (paymentRef) is
 * replayed with different payload values, indicating a potential duplicate
 * request with conflicting data.
 *
 * Should map to HTTP 409 Conflict.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }

    public IdempotencyConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
