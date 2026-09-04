package com.positivity.securityservice.internal.exception;

/**
 * Thrown when a token operation is otherwise valid — the credentials or refresh token check
 * out — but the referenced user account currently has no roles assigned, so a token cannot be
 * issued with a non-empty {@code roles}/authorities claim.
 *
 * <p>This is a refusal about the caller's authorization, not about the request (ADR-0017 §2,
 * question 1): the credentials or refresh token are fine, but the account holds no roles and so
 * no effective permissions, and only an administrator can change that. Mapped to {@code 403
 * Forbidden} with code {@code USER_HAS_NO_ROLES} by {@code GlobalExceptionHandler}, on every
 * entry point that can reach it (credential login and refresh alike — one condition, one status).
 */
public class NoRolesAssignedException extends RuntimeException {

    public NoRolesAssignedException(String message) {
        super(message);
    }
}
