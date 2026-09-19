package com.positivity.price.internal.exception;

/**
 * Thrown by a declared placeholder operation that has no implementation yet. Maps to HTTP 501
 * with the ApiError envelope (code NOT_IMPLEMENTED), so the placeholder answers like every other
 * error instead of with an empty body (ADR-0017 §3, #1720).
 */
public class OperationNotImplementedException extends RuntimeException {

    public OperationNotImplementedException(String message) {
        super(message);
    }
}
