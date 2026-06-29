package com.positivity.invoice.internal.exception;

/**
 * Raised when manager-approval elevation cannot be granted: the employee number does
 * not resolve to an active employee, or that person does not hold
 * {@code invoice:finalize:override}. Maps to HTTP 401 — the approval is rejected without
 * disclosing which check failed.
 */
public class ElevationDeniedException extends RuntimeException {

    public ElevationDeniedException(String message) {
        super(message);
    }
}
