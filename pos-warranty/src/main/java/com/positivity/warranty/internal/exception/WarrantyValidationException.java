package com.positivity.warranty.internal.exception;

/**
 * A genuine client input-validation failure in this module — a required field missing from a
 * request, a malformed combination of request values, a cross-field precondition the caller's
 * payload failed to satisfy. Services and controllers must throw this (never a bare {@code
 * IllegalArgumentException}) for that case: {@link WarrantyExceptionHandler} maps it to {@code
 * 400 VALIDATION_ERROR}, echoing the message (ADR-0017 §1: "malformed requests and
 * request-shape/field validation errors").
 *
 * <p>Bare {@code IllegalArgumentException} must not be used for input validation here because it
 * is not exclusive to this module's own throws — it is also what Hibernate/JPA throw for an
 * invalid JPQL query and what {@code UUID.fromString} throws on malformed stored data. Routing
 * that type through a blanket {@code @ExceptionHandler(IllegalArgumentException.class)} reports a
 * server-side defect as a client {@code 400}, leaking internal class names and query text into
 * the response body. A type this module controls, thrown only where the module itself validates
 * its own input, cannot be confused with an unrelated persistence or parsing failure: anything
 * else typed {@code IllegalArgumentException} now falls through to the platform's generic 500
 * handler ({@code pos-web-common}'s {@code GlobalApiExceptionHandler}).
 *
 * <p>A well-formed request that violates a documented domain-policy rule about the resource's
 * <em>current data</em> (rather than the shape of this request) belongs to {@link
 * WarrantyUnprocessableException} (422) instead — see its Javadoc.
 */
public class WarrantyValidationException extends RuntimeException {

    public WarrantyValidationException(String message) {
        super(message);
    }
}
