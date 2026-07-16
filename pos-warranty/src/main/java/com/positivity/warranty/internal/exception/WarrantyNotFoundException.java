package com.positivity.warranty.internal.exception;

/**
 * Thrown when a warranty-domain resource referenced by id does not exist. Translated by
 * {@link WarrantyExceptionHandler} into a 404 {@code ApiError} envelope whose {@code code}
 * is the machine-readable value carried here (e.g. {@code WARRANTY_PROVIDER_NOT_FOUND}).
 */
public class WarrantyNotFoundException extends RuntimeException {

    private final String code;

    public WarrantyNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
