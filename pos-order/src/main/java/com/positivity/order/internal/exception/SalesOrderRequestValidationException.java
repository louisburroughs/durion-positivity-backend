package com.positivity.order.internal.exception;

/**
 * A sales-order/cart request is malformed on its face: a half-specified deposit-source pair, a
 * missing {@code locationId} with no open register session to fall back to, a blank
 * {@code Idempotency-Key} at checkout, an unsupported {@code tenderType}, or a {@code status}/
 * {@code sourceType} filter that does not name a known enum value (issue #1694). Distinct from an
 * {@link IllegalStateException} raised by this same controller family, which stays a
 * well-formed-request-refused-by-current-state 422/409. Maps to a 400 {@code ORDER_INVALID_ARGUMENT}
 * ApiError — the code is unchanged from the blanket {@code IllegalArgumentException} handler this
 * type replaces; only the status moved, from 422 to 400 per ADR-0017.
 */
public class SalesOrderRequestValidationException extends RuntimeException {

    public SalesOrderRequestValidationException(String message) {
        super(message);
    }
}
