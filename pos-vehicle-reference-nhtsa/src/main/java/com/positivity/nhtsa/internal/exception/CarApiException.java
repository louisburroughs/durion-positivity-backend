package com.positivity.nhtsa.internal.exception;

public class CarApiException extends RuntimeException {
    public CarApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
