package com.positivity.invoice.internal.exception;

/**
 * Thrown when a payment intent is not found, or does not belong to the expected
 * invoice.
 * Maps to HTTP 404 Not Found (ADR-0017).
 *
 * Story #9.
 */
public class PaymentIntentNotFoundException extends RuntimeException {

    public PaymentIntentNotFoundException(String message) {
        super(message);
    }
}