package com.positivity.accounting.internal.exception;

/**
 * Thrown when a client-supplied date range or window is malformed (end
 * before start, or a window wider than the endpoint's documented maximum).
 * Maps to HTTP 400 (VALIDATION_ERROR) — a request-shape validation failure
 * per ADR-0017 §1, not a domain-policy or server condition.
 */
public class InvalidDateRangeException extends RuntimeException {

    public InvalidDateRangeException(String message) {
        super(message);
    }
}
