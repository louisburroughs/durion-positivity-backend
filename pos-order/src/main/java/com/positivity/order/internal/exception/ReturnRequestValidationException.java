package com.positivity.order.internal.exception;

/**
 * A return request is malformed on its face — no lines, a duplicated or unknown order-line
 * reference, a non-positive {@code returnQty}, or a {@code refundMethod}/{@code condition} that
 * does not name a known enum value (issue #1694). Distinct from {@link OverCapReturnException}
 * (a well-formed request the returnable remainder refuses) and from a not-returnable line, which
 * is a documented domain-policy violation of an otherwise-valid payload (see
 * {@link ReturnLineNotReturnableException}). Maps to a 400 {@code RETURN_INVALID_ARGUMENT}
 * ApiError — the code is unchanged from the blanket {@code IllegalArgumentException} handler this
 * type replaces; only the status moved, from 422 to 400 per ADR-0017.
 */
public class ReturnRequestValidationException extends RuntimeException {

    public ReturnRequestValidationException(String message) {
        super(message);
    }
}
