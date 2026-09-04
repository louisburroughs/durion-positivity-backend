package com.positivity.people.internal.exception;

/**
 * A genuine client input-validation failure in this module — a blank required field, a
 * malformed combination of request values, an unparseable field format (a date, a time zone).
 * Controllers and services must throw this (never bare {@code IllegalArgumentException}) for
 * that case: {@code PeopleExceptionHandler} maps it to {@code 400 VALIDATION_ERROR}, echoing
 * the message.
 *
 * <p>Bare {@code IllegalArgumentException} must not be used for input validation here because
 * it is not exclusive to this module's own throws — it is also what Hibernate/JPA throw for an
 * invalid query and what {@code UUID.fromString} throws on malformed stored data. When {@code
 * PeopleExceptionHandler} used to catch {@code IllegalArgumentException} itself, a server-side
 * defect could be reported back to the client as a {@code 400} carrying the raw internal
 * exception message — a server defect misreported as a client error, leaking internal class
 * names and query text. A type this module controls, thrown only where the module itself
 * validates its own input, cannot be confused with an unrelated persistence or parsing
 * failure: anything else typed {@code IllegalArgumentException} now falls through to the
 * platform's generic 500 handler ({@code pos-web-common}'s {@code GlobalApiExceptionHandler}).
 *
 * <p>A check that instead depends on the current state of an entity (a status that blocks the
 * requested transition) is a stateful collision, not a field-validation failure, and belongs
 * on {@link IllegalStateException} (mapped {@code 409}) — not this type.
 */
public class RequestValidationException extends RuntimeException {

    public RequestValidationException(String message) {
        super(message);
    }

    public RequestValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
