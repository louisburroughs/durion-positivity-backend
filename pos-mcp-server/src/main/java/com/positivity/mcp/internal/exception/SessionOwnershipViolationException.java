package com.positivity.mcp.internal.exception;

public class SessionOwnershipViolationException extends RuntimeException {
    public SessionOwnershipViolationException(String message) {
        super(message);
    }
}
