package com.positivity.order.internal.exception;

/**
 * A register-session request is malformed on its face: a non-positive cash-movement amount, or a
 * {@code movementType} that does not name a {@link com.positivity.order.internal.entity.CashMovementType}
 * (issue #1694). Distinct from {@link RegisterSessionConflictException}, which is a well-formed
 * request the session's current state refuses. Maps to a 400 {@code REGISTER_SESSION_INVALID_ARGUMENT}
 * ApiError — the code is unchanged from the blanket {@code IllegalArgumentException} handler this
 * type replaces; only the status moved, from 422 to 400 per ADR-0017 (request-shape validation, not
 * a domain-policy refusal of an otherwise-valid payload).
 */
public class RegisterSessionRequestValidationException extends RuntimeException {

    public RegisterSessionRequestValidationException(String message) {
        super(message);
    }
}
