package com.positivity.location.internal.exception;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception for duplicate-resource conflicts.
 *
 * <p>{@code field}, when given, names the request field the duplicate value came in on (for
 * example {@code unitNumber}), so {@code LocationGlobalExceptionHandler} can answer with
 * {@code fieldErrors} naming it (#2267 MOBILE_UNIT_IDENTITY_TAKEN) instead of the bare code every
 * other conflict here answers with.
 *
 * Issue: #76
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateResourceException extends IllegalStateException {

    private final @Nullable String field;

    public DuplicateResourceException(String message) {
        this(message, null);
    }

    public DuplicateResourceException(String message, @Nullable String field) {
        super(message);
        this.field = field;
    }

    /** The request field at fault, or {@code null} when this conflict names no single field. */
    public @Nullable String getField() {
        return field;
    }
}
