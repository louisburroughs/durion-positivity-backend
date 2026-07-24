package com.positivity.order.internal.exception;

import java.util.UUID;

/**
 * A return order was looked up by an id that does not exist (parity stories F1/F2). Maps to a 404
 * {@code RETURN_NOT_FOUND} ApiError.
 */
public class ReturnOrderNotFoundException extends RuntimeException {

    public ReturnOrderNotFoundException(UUID returnOrderId) {
        super("Return order not found: " + returnOrderId);
    }
}
