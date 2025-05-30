package com.positivity.nhtsa.exception;

public class CarApiException extends RuntimeException {
    public CarApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
