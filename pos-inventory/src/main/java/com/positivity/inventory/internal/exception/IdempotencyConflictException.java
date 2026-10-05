package com.positivity.inventory.internal.exception;

/**
 * Thrown when an {@code Idempotency-Key} that already recorded a receipt is reused for a different
 * request. Maps to 409 {@code IDEMPOTENCY_CONFLICT}: the key is taken, so the call is neither
 * replayed nor posted.
 */
public class IdempotencyConflictException extends RuntimeException {

    public static final String ERROR_CODE = "IDEMPOTENCY_CONFLICT";

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
