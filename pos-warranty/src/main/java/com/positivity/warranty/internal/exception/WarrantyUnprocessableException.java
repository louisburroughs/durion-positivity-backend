package com.positivity.warranty.internal.exception;

/**
 * Raised when a request is well-formed but references cross-service data that cannot be
 * resolved or acted on (e.g. a settlement linking a replacement workorder that pos-workorder
 * does not know). Translated by {@link WarrantyExceptionHandler} into a 422 {@code ApiError}
 * envelope carrying the machine-readable {@code code}.
 */
public class WarrantyUnprocessableException extends RuntimeException {

    private final String code;

    public WarrantyUnprocessableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
