package com.positivity.order.internal.exception;

import java.util.UUID;

/**
 * A register session was looked up by an id that does not exist (parity stories G1/G2). Maps to a
 * 404 {@code REGISTER_SESSION_NOT_FOUND} ApiError.
 */
public class RegisterSessionNotFoundException extends RuntimeException {

    public RegisterSessionNotFoundException(UUID sessionId) {
        super("Register session not found: " + sessionId);
    }
}
