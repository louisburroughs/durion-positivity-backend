package com.positivity.accounting.internal.exception;

import org.jspecify.annotations.Nullable;

/**
 * Exception thrown when payment gateway processing fails.
 *
 * This indicates a failure in the payment gateway integration layer,
 * not a validation error in the request.
 *
 * Should map to HTTP 500 Internal Server Error or 502 Bad Gateway.
 */
public class PaymentGatewayException extends RuntimeException {

    private final @Nullable String paymentRef;

    public PaymentGatewayException(String message) {
        super(message);
        this.paymentRef = null;
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
        this.paymentRef = null;
    }

    public PaymentGatewayException(String message, String paymentRef, Throwable cause) {
        super(message, cause);
        this.paymentRef = paymentRef;
    }

    public @Nullable String getPaymentRef() {
        return paymentRef;
    }
}
