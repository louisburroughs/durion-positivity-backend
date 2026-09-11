package com.positivity.mcp.internal.exception;

/**
 * An NLTI session id that does not exist for the bound tenant (ADR-0062 plan WS6, R-B6). Raised for
 * an id of another tenant exactly as for an id that never existed: the two are indistinguishable
 * on purpose, so the response reveals nothing about other tenants' sessions.
 */
public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(String message) {
        super(message);
    }
}
