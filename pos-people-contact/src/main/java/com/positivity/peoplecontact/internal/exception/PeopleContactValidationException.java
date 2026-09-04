package com.positivity.peoplecontact.internal.exception;

/**
 * A genuine client input-validation failure in this module — a blank required field, a
 * malformed combination of request values, an identifier pos-security-service itself rejected
 * as invalid. Services, clients, and controllers must throw this (never bare {@code
 * IllegalArgumentException}) for that case: {@code PeopleExceptionHandler} maps it to
 * {@code 400 VALIDATION_ERROR}, echoing the message.
 *
 * <p>Bare {@code IllegalArgumentException} must not be used for input validation here because
 * it is not exclusive to this module's own throws — it is also what Hibernate/JPA throw for an
 * invalid query and what {@code UUID.fromString} throws on malformed stored data. A blanket
 * {@code @ExceptionHandler(IllegalArgumentException.class)} would report either of those
 * server-side defects back to the client as a {@code 400} carrying the raw internal message
 * (issue #1694). A type this module controls, thrown only where the module itself validates
 * its own input (or a downstream service's own request-shape rejection), cannot be confused
 * with an unrelated persistence or parsing failure: anything else typed {@code
 * IllegalArgumentException} now falls through to the platform's generic 500 handler ({@code
 * pos-web-common}'s {@code GlobalApiExceptionHandler}).
 */
public class PeopleContactValidationException extends RuntimeException {

    public PeopleContactValidationException(String message) {
        super(message);
    }

    public PeopleContactValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
