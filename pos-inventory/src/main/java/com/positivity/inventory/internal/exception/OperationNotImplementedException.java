package com.positivity.inventory.internal.exception;

/**
 * Thrown by an operation that is deliberately unimplemented. Maps to HTTP 501 with the ApiError
 * envelope (code NOT_IMPLEMENTED), so the operation answers like every other error instead of with
 * an empty body (ADR-0017 §3, #1720).
 */
public class OperationNotImplementedException extends RuntimeException {

    public OperationNotImplementedException(String message) {
        super(message);
    }
}
