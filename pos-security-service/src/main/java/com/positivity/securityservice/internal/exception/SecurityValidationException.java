package com.positivity.securityservice.internal.exception;

/**
 * A genuine client input-validation failure in this module — a blank required field, a
 * malformed permission key or scope/location combination, a reference (role name, user id) that
 * does not resolve, a malformed Base64URL bitset, an unsupported {@code perm_ver}. Services and
 * controllers must throw this (never bare {@code IllegalArgumentException}) for that case:
 * {@code GlobalExceptionHandler} maps it to {@code 400 VALIDATION_ERROR} (ADR-0017 §1), echoing
 * the message. That is the same code the other modules that introduced a fresh generic
 * validation type in #1694 converged on — pos-people, pos-people-contact, pos-warranty,
 * pos-accounting and pos-customer. It answered {@code INVALID_REQUEST} until #1730; the type was
 * new, so there was no wire contract to preserve.
 *
 * <p>Bare {@code IllegalArgumentException} must not be used for input validation here because it
 * is not exclusive to this module's own throws — it is also what Hibernate/JPA throw for an
 * invalid query and what {@code UUID.fromString} throws on malformed stored data. In an auth
 * service, a blanket {@code @ExceptionHandler(IllegalArgumentException.class)} reported such
 * server-side defects back to the client as a {@code 400} validation error, leaking internal
 * class names and query text (issue #1694). A type this module controls, thrown only where the
 * module itself validates its own input, cannot be confused with an unrelated persistence or
 * parsing failure: anything else typed {@code IllegalArgumentException} now falls through to the
 * platform's generic 500 handler ({@code pos-web-common}'s {@code GlobalApiExceptionHandler}).
 */
public class SecurityValidationException extends RuntimeException {

    public SecurityValidationException(String message) {
        super(message);
    }

    public SecurityValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
