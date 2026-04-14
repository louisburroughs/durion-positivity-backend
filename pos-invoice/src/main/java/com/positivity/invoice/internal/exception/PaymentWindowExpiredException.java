package com.positivity.invoice.internal.exception;

public class PaymentWindowExpiredException extends RuntimeException {

    public PaymentWindowExpiredException(String message) {
        super(message);
    }
}
