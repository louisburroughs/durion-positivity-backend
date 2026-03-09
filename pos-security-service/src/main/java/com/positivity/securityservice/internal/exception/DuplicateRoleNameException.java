package com.positivity.securityservice.internal.exception;

/**
 * Exception thrown when attempting to create a role with a name that already
 * exists (case-insensitive uniqueness check).
 *
 * <p>
 * Mapped to HTTP 409 Conflict by
 * {@link com.positivity.securityservice.internal.config.GlobalExceptionHandler}.
 *
 * <p>
 * Story #62 — Define Shop Roles and Permission Matrix.
 */
public class DuplicateRoleNameException extends RuntimeException {

    public DuplicateRoleNameException(String message) {
        super(message);
    }

    public DuplicateRoleNameException(String message, Throwable cause) {
        super(message, cause);
    }
}
