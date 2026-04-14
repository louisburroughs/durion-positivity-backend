package com.positivity.invoice.internal.exception;

/**
 * Thrown when a payment gateway operation fails.
 *
 * Story #8.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
