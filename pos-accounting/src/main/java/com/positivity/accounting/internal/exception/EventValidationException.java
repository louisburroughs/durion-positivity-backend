package com.positivity.accounting.internal.exception;

/**
 * Thrown when an inbound accounting event (submission or reprocessing
 * payload) fails structural validation: a required field is missing, a
 * value cannot be parsed (UUID, ISO-8601 datetime), or a default-mapping
 * posting requires an amount field the event payload does not carry. Maps
 * to HTTP 400 (VALIDATION_ERROR) per ADR-0017 §1.
 */
public class EventValidationException extends RuntimeException {

    public EventValidationException(String message) {
        super(message);
    }

    public EventValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
