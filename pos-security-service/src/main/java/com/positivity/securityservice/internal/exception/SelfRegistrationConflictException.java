package com.positivity.securityservice.internal.exception;

public class SelfRegistrationConflictException extends RuntimeException {

    private final String errorCode;
    private final java.util.UUID referenceId;

    public SelfRegistrationConflictException(String errorCode, String message) {
        this(errorCode, message, null);
    }

    public SelfRegistrationConflictException(String errorCode, String message, java.util.UUID referenceId) {
        super(message);
        this.errorCode = errorCode;
        this.referenceId = referenceId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public java.util.UUID getReferenceId() {
        return referenceId;
    }
}
