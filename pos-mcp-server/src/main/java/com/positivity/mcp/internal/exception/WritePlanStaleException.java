package com.positivity.mcp.internal.exception;

/**
 * Gate 6 (G6.7): a source entity version captured at plan time changed before confirmation -- the
 * plan is cancelled and the caller must re-preview against the current data.
 */
public class WritePlanStaleException extends RuntimeException {
    public WritePlanStaleException(String message) {
        super(message);
    }
}
