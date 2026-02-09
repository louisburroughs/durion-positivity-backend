package com.positivity.accounting.internal.exception;

/**
 * Exception thrown when payment gateway processing fails.
 * 
 * This indicates a failure in the payment gateway integration layer,
 * not a validation error in the request.
 * 
 * Should map to HTTP 500 Internal Server Error or 502 Bad Gateway.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
