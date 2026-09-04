package com.positivity.warranty.internal.exception;

/**
 * Raised when a request is well-formed but violates a documented domain-policy rule about the
 * resource's current data — a semantically valid payload that the domain still rejects (ADR-0017
 * §2), such as a settlement linking a replacement workorder that pos-workorder does not know, or a
 * claim submitted without the photo evidence its winning policy requires. Translated by {@link
 * WarrantyExceptionHandler} into a 422 {@code ApiError} envelope carrying the machine-readable
 * {@code code}.
 *
 * <p>Contrast with {@link WarrantyValidationException} (400): that type is for malformed
 * requests / request-shape / field validation, where the payload itself is defective; this type
 * is for a structurally valid request that the domain's own rules still refuse.
 */
public class WarrantyUnprocessableException extends RuntimeException {

    private final String code;

    public WarrantyUnprocessableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
