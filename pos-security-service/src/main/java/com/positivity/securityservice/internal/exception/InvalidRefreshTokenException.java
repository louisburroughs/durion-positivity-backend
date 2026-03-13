package com.positivity.securityservice.internal.exception;

/**
 * Thrown when a refresh token is valid JWT cryptographically but cannot be
 * used to issue a new token pair (user deleted, token revoked, etc.).
 */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
