package com.positivity.securityservice.internal.exception;

/**
 * The activation token presented to {@code POST /v1/auth/activate} is unknown, expired or already
 * used. One exception for the three conditions on purpose: the caller is unauthenticated, and
 * distinguishing them would tell an attacker which guesses were once real tokens. Mapped to 401
 * {@code ACTIVATION_TOKEN_INVALID} by {@code GlobalExceptionHandler}.
 */
public class ActivationTokenInvalidException extends RuntimeException {

    public ActivationTokenInvalidException() {
        super("Activation token is invalid, expired or already used");
    }
}
