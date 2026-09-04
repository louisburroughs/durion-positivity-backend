package com.positivity.invoice.internal.exception;

/**
 * A request rejected because it is malformed, missing a required field, or internally
 * inconsistent (issue #1694) — a genuine client input-validation failure reachable from an HTTP
 * request. Maps to HTTP 400 per ADR-0017 §1 ("malformed requests and request-shape/field
 * validation errors").
 *
 * <p>Deliberately does NOT extend {@link IllegalArgumentException}: Hibernate/JPA, {@code
 * UUID.fromString}, and {@code Enum.valueOf} all throw {@code IllegalArgumentException} for
 * reasons that have nothing to do with a client's request (an invalid query, malformed stored
 * data, ...), so a controller advice that blanket-catches {@code IllegalArgumentException} risks
 * reporting a server-side defect as a client 4xx and leaking internal detail in the response
 * body. This type is thrown only at sites that intentionally validate a caller-supplied value.
 */
public class InvoiceRequestValidationException extends RuntimeException {

    public InvoiceRequestValidationException(String message) {
        super(message);
    }

    public InvoiceRequestValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
