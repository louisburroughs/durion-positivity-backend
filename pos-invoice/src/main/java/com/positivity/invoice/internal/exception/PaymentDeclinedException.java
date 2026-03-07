package com.positivity.invoice.internal.exception;

/**
 * Thrown when the payment gateway definitively declines a payment.
 * Maps to HTTP 422 Unprocessable Entity (ADR-0017).
 * Story #9.
 */
public class PaymentDeclinedException extends RuntimeException {
    public PaymentDeclinedException(String message) {
        super(message);
    }
}