package com.positivity.order.internal.exception;

/**
 * A register-session operation collided with the session's current state (parity stories G1/G2):
 * a second open on a terminal that already has an OPEN session (spec R6.1), a cash movement or
 * close against a non-OPEN session, or a confirm-close before begin-close. Maps to a 409
 * {@code REGISTER_SESSION_CONFLICT} ApiError.
 */
public class RegisterSessionConflictException extends RuntimeException {

    public RegisterSessionConflictException(String message) {
        super(message);
    }
}
