package com.positivity.order.internal.exception;

import java.util.UUID;

/**
 * A void was requested on an order that already has settled payments (parity story C5, spec
 * R2.4): settled money must be reversed through the cancellation saga, never silently dropped by
 * a void. Maps to a 409 {@code ORDER_VOID_BLOCKED} ApiError pointing the caller at cancel.
 */
public class OrderVoidBlockedException extends RuntimeException {

    public OrderVoidBlockedException(UUID orderId) {
        super("Order " + orderId + " has settled payments and cannot be voided; use the cancellation flow"
                + " (POST /v1/orders/" + orderId + "/cancel) so payments are reversed");
    }
}
