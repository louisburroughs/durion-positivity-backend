package com.positivity.order.internal.exception;

/**
 * A well-formed cancellation request that the order's current state refuses: an order not in a
 * cancellable status, a retry from a state other than CANCEL_FAILED_BILLING, a workorder the
 * work-execution service reports as non-cancellable, or settled payments with no invoice
 * reference to reverse against (issue #1730). ADR-0017 §2 makes a stateful collision a
 * {@code 409}; maps to {@code ORDER_CANCELLATION_INVALID}, the code and status this case already
 * answered.
 *
 * <p>Split out of bare {@link IllegalStateException}, which in this service also carried
 * downstream call failures ("workorder cancellation failed", "payment reversal failed"). Those
 * are server-side problems and stay untyped so the platform advice answers a correlated 500,
 * rather than telling the caller its request conflicts with state it cannot see.
 */
public class OrderCancellationStateConflictException extends RuntimeException {

    public OrderCancellationStateConflictException(String message) {
        super(message);
    }
}
