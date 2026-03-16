package com.positivity.securityservice.internal.exception;

public class SelfRegistrationConflictException extends RuntimeException {

    private final String errorCode;

    public SelfRegistrationConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
