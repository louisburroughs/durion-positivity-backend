package com.positivity.mcp.internal.exception;

/** Gate 6: no write plan exists for the referenced NLTI request. */
public class WritePlanNotFoundException extends RuntimeException {
    public WritePlanNotFoundException(String message) {
        super(message);
    }
}
