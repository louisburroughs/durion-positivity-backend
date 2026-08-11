package com.positivity.mcp.internal.exception;

/** Gate 6: executing the confirmed write plan's tool call failed downstream. */
public class WritePlanExecutionException extends RuntimeException {
    public WritePlanExecutionException(String message) {
        super(message);
    }

    public WritePlanExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
