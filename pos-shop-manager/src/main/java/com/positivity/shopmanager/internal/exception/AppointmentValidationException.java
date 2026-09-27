package com.positivity.shopmanager.internal.exception;

import org.jspecify.annotations.Nullable;

/**
 * A genuine client input-validation failure raised by this module's own services or controllers
 * (400 {@code VALIDATION_ERROR}). {@code field}, when given, names the offending request field so
 * {@link com.positivity.shopmanager.internal.controller.GlobalExceptionHandler} can attach it as a
 * single {@code fieldErrors} entry (e.g. a contradictory {@code resourceId}/{@code resourceType}
 * pair, DECISION-SHOPMGMT-021); most callers pass only a message and get none, matching this
 * module's general reluctance to echo internal field names on every validation failure.
 */
public class AppointmentValidationException extends RuntimeException {

    private final @Nullable String field;

    public AppointmentValidationException(String message) {
        this(message, null);
    }

    public AppointmentValidationException(String message, @Nullable String field) {
        super(message);
        this.field = field;
    }

    public @Nullable String getField() {
        return field;
    }
}
