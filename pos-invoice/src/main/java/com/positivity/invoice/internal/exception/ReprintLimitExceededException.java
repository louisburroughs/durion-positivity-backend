package com.positivity.invoice.internal.exception;

public class ReprintLimitExceededException extends RuntimeException {

    public ReprintLimitExceededException(String message) {
        super(message);
    }
}
