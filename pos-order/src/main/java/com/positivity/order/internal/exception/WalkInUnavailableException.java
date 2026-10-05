package com.positivity.order.internal.exception;

/**
 * Walk-in was chosen but the customer replica holds no active CASH house account for the tenant
 * (CAP:550 S8) — the account is not provisioned yet, or its party fact has not been replayed into
 * this module. Nothing is substituted: the cashier picks a registered customer, or an operator
 * replays party facts. Maps to {@code 422 ORDER_WALK_IN_UNAVAILABLE}.
 */
public class WalkInUnavailableException extends RuntimeException {

    public WalkInUnavailableException() {
        super("Walk-in is not available yet: no walk-in customer is set up for this business. "
                + "Choose a registered customer");
    }
}
