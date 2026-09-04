package com.positivity.workorder.internal.exception;

/**
 * A workorder-domain request fails field-level or request-shape validation: a required value is
 * missing or blank, a numeric field is out of range, or two fields on the same request are
 * mutually inconsistent (issue #1694).
 *
 * <p>Answered as {@code 400} (ADR-0017 canonical matrix) with the {@code INVALID_ARGUMENT} code
 * the module's former blanket {@code IllegalArgumentException} handler used, so the wire contract
 * for genuine client validation failures does not drift now that the handler distinguishes them
 * from server-side defects (Hibernate/JPA lookups, malformed stored data) that used to share the
 * same exception type and be misreported as the caller's fault.
 */
public class WorkorderRequestValidationException extends RuntimeException {

    public static final String ERROR_CODE = "INVALID_ARGUMENT";

    public WorkorderRequestValidationException(String message) {
        super(message);
    }
}
