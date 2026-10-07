package com.positivity.accounting.internal.exception;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * General-purpose exception for malformed or invalid request fields/parameters
 * that do not warrant a more specific type: required-field checks, unparseable
 * identifiers, out-of-range simple values, and similar request-shape problems.
 * Maps to HTTP 400 (VALIDATION_ERROR) per ADR-0017 §1; one built with
 * {@link #forField} names the offending field in {@code fieldErrors}.
 */
public class InvalidRequestParameterException extends RuntimeException {

    private final @Nullable String field;

    public InvalidRequestParameterException(String message) {
        super(message);
        this.field = null;
    }

    public InvalidRequestParameterException(String message, Throwable cause) {
        super(message, cause);
        this.field = null;
    }

    private InvalidRequestParameterException(@NonNull String field, @NonNull String message) {
        super(message);
        this.field = field;
    }

    /** A refusal of one field: the response's {@code fieldErrors} names {@code field} with {@code message}. */
    public static @NonNull InvalidRequestParameterException forField(@NonNull String field, @NonNull String message) {
        return new InvalidRequestParameterException(field, message);
    }

    /** The offending field, when the refusal names one. */
    public @Nullable String getField() {
        return field;
    }
}
