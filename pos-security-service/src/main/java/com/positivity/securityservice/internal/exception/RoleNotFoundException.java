package com.positivity.securityservice.internal.exception;

/**
 * Exception thrown when a role is not found by ID or name.
 * 
 * Should map to HTTP 404 Not Found.
 */
public class RoleNotFoundException extends RuntimeException {

    public RoleNotFoundException(String message) {
        super(message);
    }

    public RoleNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
