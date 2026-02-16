package com.positivity.securityservice.internal.exception;

/**
 * Exception thrown when a permission is not found by name.
 * 
 * Should map to HTTP 404 Not Found.
 */
public class PermissionNotFoundException extends RuntimeException {

    public PermissionNotFoundException(String message) {
        super(message);
    }

    public PermissionNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
