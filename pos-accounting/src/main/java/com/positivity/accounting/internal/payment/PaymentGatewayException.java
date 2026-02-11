package com.positivity.accounting.internal.payment;

/**
 * Exception thrown when payment gateway operations fail.
 *
 * This exception wraps underlying gateway-specific exceptions and provides
 * a uniform interface for payment processing failures.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
