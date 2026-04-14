package com.positivity.securityservice.internal.exception;

/**
 * Exception thrown when a user is not found by ID or username.
 *
 * Should map to HTTP 404 Not Found.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String message) {
        super(message);
    }

    public UserNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
