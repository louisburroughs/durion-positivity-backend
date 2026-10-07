package com.positivity.order.internal.exception;

/**
 * A cash-movement {@code requestId} already recorded with a different payload (CAP:550 S16, #2512;
 * spec §8.2): answered 409 {@code IDEMPOTENCY_CONFLICT}.
 */
public class CashMovementIdempotencyConflictException extends RuntimeException {

    public CashMovementIdempotencyConflictException(String message) {
        super(message);
    }
}
