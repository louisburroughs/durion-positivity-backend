package com.positivity.order.internal.exception;

/**
 * A well-formed sales-order request that the current state of something else refuses — today,
 * a new order against a terminal whose register session is mid-close (issue #1730). ADR-0017 §2
 * makes a stateful collision a {@code 409}; maps to {@code ORDER_STATE_CONFLICT}.
 *
 * <p>Split out of bare {@link IllegalStateException} for the reason #1694 split
 * {@link IllegalArgumentException}: that type carried at least four different meanings in this
 * module — a stateful collision, a domain-policy refusal on a valid payload, a request-shape
 * error, and an outright server-side failure — so no single status was right for it, and the
 * four sales/return/cancellation/purchase advices had settled on two different wrong answers.
 * Lifecycle collisions raised by {@code OrderStateMachine} keep their own types
 * ({@link OrderNotEditableException}, {@link InvalidOrderStateTransitionException}); this one is
 * for guards the services raise directly.
 */
public class SalesOrderStateConflictException extends RuntimeException {

    public SalesOrderStateConflictException(String message) {
        super(message);
    }
}
