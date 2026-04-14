package com.positivity.invoice.internal.exception;

/**
 * Thrown when a payment operation is invalid given the current payment intent
 * state.
 * Maps to HTTP 409 Conflict (ADR-0017).
 *
 * Story #9.
 */
public class InvalidPaymentStateException extends RuntimeException {

    public InvalidPaymentStateException(String message) {
        super(message);
    }

    public InvalidPaymentStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
