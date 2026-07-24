package com.positivity.order.internal.exception;

import java.util.UUID;

/**
 * A register session close was requested while one or more of its orders are still in
 * PENDING_PAYMENT (parity story G2, spec R6.2). Drafts may park across sessions, but unsettled
 * checkouts must resolve before the drawer can be reconciled. Maps to a 409
 * {@code SESSION_CLOSE_BLOCKED} ApiError.
 */
public class SessionCloseBlockedException extends RuntimeException {

    public SessionCloseBlockedException(UUID sessionId) {
        super("Register session " + sessionId + " has orders in PENDING_PAYMENT and cannot be closed;"
                + " settle or void them first");
    }
}
