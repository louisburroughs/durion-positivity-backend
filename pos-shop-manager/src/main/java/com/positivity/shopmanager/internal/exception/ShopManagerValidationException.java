package com.positivity.shopmanager.internal.exception;

/**
 * A genuine client input-validation failure in this module — a blank required field, a
 * malformed combination of request values, an unsatisfiable business precondition supplied
 * by the caller. Services and controllers must throw this (never bare {@code
 * IllegalArgumentException}) for that case: {@code GlobalExceptionHandler} maps it to
 * {@code 400 INVALID_REQUEST}, echoing the message.
 *
 * <p>Bare {@code IllegalArgumentException} must not be used for input validation here because
 * it is not exclusive to this module's own throws — it is also what Hibernate/JPA throw for an
 * invalid JPQL query and what {@code UUID.fromString} throws on malformed stored data. When
 * {@code GlobalExceptionHandler} used to catch {@code IllegalArgumentException} itself (issue
 * #1686), a broken repository query in issue #1679 was reported back to the client as a
 * {@code 400 INVALID_REQUEST} carrying the raw {@code UnknownPathException} message and JPQL —
 * a server defect misreported as a client error, leaking internal class names and query text.
 * A type this module controls, thrown only where the module itself validates its own input,
 * cannot be confused with an unrelated persistence or parsing failure: anything else typed
 * {@code IllegalArgumentException} now falls through to the platform's generic 500 handler
 * ({@code pos-web-common}'s {@code GlobalApiExceptionHandler}).
 */
public class ShopManagerValidationException extends RuntimeException {

    public ShopManagerValidationException(String message) {
        super(message);
    }
}
