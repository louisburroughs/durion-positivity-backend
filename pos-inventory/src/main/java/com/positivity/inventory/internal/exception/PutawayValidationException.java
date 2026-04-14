package com.positivity.inventory.internal.exception;

/**
 * Base exception for putaway validation errors.
 * Thrown when a putaway operation fails business rule validation.
 */
public class PutawayValidationException extends RuntimeException {
    private final String errorCode;

    public PutawayValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PutawayValidationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
