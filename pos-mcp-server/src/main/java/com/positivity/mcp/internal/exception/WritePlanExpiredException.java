package com.positivity.mcp.internal.exception;

/** Gate 6: the write plan's confirmation TTL has elapsed -- confirming must not execute (G6.6). */
public class WritePlanExpiredException extends RuntimeException {
    public WritePlanExpiredException(String message) {
        super(message);
    }
}
