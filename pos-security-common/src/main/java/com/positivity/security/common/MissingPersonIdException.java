package com.positivity.security.common;

/**
 * Thrown when a flow requires an authenticated stable person identifier but it
 * is missing from the security context.
 */
public class MissingPersonIdException extends IllegalStateException {
    public MissingPersonIdException(String message) {
        super(message);
    }
}
