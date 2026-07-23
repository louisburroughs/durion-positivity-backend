package com.positivity.order.internal.exception;

/**
 * Thrown when an {@code Idempotency-Key} or client line uuid is replayed with a payload that does
 * not match the original request (plan story I1).
 */
public class CartIdempotencyConflictException extends RuntimeException {

    public CartIdempotencyConflictException(String message) {
        super(message);
    }
}
