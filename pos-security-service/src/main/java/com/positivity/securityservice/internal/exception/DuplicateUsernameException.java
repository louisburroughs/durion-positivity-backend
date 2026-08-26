package com.positivity.securityservice.internal.exception;

/**
 * Thrown when a user-creation request names a username that already exists.
 * Mapped to 409 Conflict (USER_ALREADY_EXISTS) by the global exception handler,
 * matching the self-registration flow's conflict semantics.
 */
public class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException(String message) {
        super(message);
    }
}
