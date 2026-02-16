package com.positivity.securityservice.internal.exception;

/**
 * Exception thrown when a role assignment is not found by ID.
 * 
 * Should map to HTTP 404 Not Found.
 */
public class RoleAssignmentNotFoundException extends RuntimeException {

    public RoleAssignmentNotFoundException(String message) {
        super(message);
    }

    public RoleAssignmentNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
