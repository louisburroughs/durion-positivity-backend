package com.positivity.securityservice.internal.exception;

/**
 * Thrown when a token that passed full validation (signature, issuer, audience, expiry,
 * revocation, token store) carries neither a {@code uid} claim nor the legacy {@code userId}
 * claim, so there is no user identifier to extract from it.
 *
 * <p>This is not a bad credential — the token is genuine and current, so {@code 401} would tell
 * the caller to replace a token that is fine — and it is not a request-shape problem, since the
 * request parsed and the token parsed. It is a well-formed request refused by a documented domain
 * rule that will keep failing until the caller presents a different token: ADR-0017 §2's third
 * question, so it answers {@code 422 Unprocessable Entity} with code {@code TOKEN_USER_ID_MISSING}
 * via {@code GlobalExceptionHandler}. Before #1803 this surfaced as a {@code NullPointerException}
 * rendered as a generic 500 by {@code pos-web-common}, which misreported a property of the
 * caller's token as a server defect.
 */
public class TokenUserIdMissingException extends RuntimeException {

    public TokenUserIdMissingException(String message) {
        super(message);
    }
}
