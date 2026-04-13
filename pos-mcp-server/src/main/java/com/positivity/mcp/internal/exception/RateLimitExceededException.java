package com.positivity.mcp.internal.exception;

public class RateLimitExceededException extends IllegalStateException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
