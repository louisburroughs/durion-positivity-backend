package com.positivity.mcp.internal.exception;

/**
 * Gate 6: the write plan is in a state that forbids the requested transition (already cancelled,
 * mid-execution, mismatched idempotency key, or an attempt to cancel an executed plan).
 */
public class WritePlanConflictException extends RuntimeException {
    public WritePlanConflictException(String message) {
        super(message);
    }
}
