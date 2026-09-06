package com.positivity.securityservice.internal.exception;

/**
 * Thrown when a token presented as a query parameter to one of the token utility endpoints
 * ({@code GET /v1/auth/roles}, {@code /subject}, {@code /user-id}) is refused by
 * {@code JwtService#validateToken} — expired, revoked, unknown to the token store, or malformed.
 *
 * <p>The condition is a bad credential, so the status is {@code 401}; what this type adds is the
 * envelope. Before the #1808 review the three controller methods answered a bare
 * {@code ResponseEntity.status(UNAUTHORIZED).build()} — no {@code ApiError} body and no
 * {@code X-Correlation-Id} header — while {@code openapi.yaml} documented an {@code ApiError}
 * body for their 401. {@code GlobalExceptionHandler} maps this to {@code 401 INVALID_TOKEN}
 * through the same {@code respond} helper as every other handler, so the body and the header
 * carry the correlation id (ADR-0017 §3/§4).
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
