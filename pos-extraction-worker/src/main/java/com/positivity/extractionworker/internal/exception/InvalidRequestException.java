package com.positivity.extractionworker.internal.exception;

/** A request field is invalid: answered 400 {@code VALIDATION_FAILED} with one field error. */
public class InvalidRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String field;

    public InvalidRequestException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
