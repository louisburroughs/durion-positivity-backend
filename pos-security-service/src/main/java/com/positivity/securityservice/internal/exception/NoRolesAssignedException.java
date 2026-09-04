package com.positivity.securityservice.internal.exception;

/**
 * Thrown when a token operation is otherwise valid — the credentials or refresh token check
 * out — but the referenced user account currently has no roles assigned, so a token cannot be
 * issued with a non-empty {@code roles}/authorities claim.
 *
 * <p>This is a domain-policy violation on an otherwise-valid payload (ADR-0017 §1/§2: "422 for
 * semantically valid requests that violate domain policy and are not representable as a
 * conflict"), not a malformed request — the refresh token itself is well-formed, unexpired,
 * unrevoked, and present in the token store. Mapped to {@code 422 Unprocessable Entity} with
 * code {@code USER_HAS_NO_ROLES} by {@code GlobalExceptionHandler}.
 */
public class NoRolesAssignedException extends RuntimeException {

    public NoRolesAssignedException(String message) {
        super(message);
    }
}
