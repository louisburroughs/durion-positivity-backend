package com.positivity.invoice.internal.exception;

public class InsufficientRefundableAmountException extends RuntimeException {

    public InsufficientRefundableAmountException(String message) {
        super(message);
    }
}
