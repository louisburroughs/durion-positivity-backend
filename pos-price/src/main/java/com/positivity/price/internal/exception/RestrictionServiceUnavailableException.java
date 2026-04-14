package com.positivity.price.internal.exception;

public class RestrictionServiceUnavailableException extends RuntimeException {
    public RestrictionServiceUnavailableException(String message) {
        super(message);
    }

    public RestrictionServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
