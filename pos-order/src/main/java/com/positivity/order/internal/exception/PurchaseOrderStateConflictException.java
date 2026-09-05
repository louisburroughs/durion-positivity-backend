package com.positivity.order.internal.exception;

/**
 * A well-formed purchase-order request that the order's current status refuses: approving one
 * that is not DRAFT, or cancelling one already fully received or closed (issue #1730).
 * ADR-0017 §2 makes a stateful collision a {@code 409}; maps to
 * {@code PURCHASE_ORDER_INVALID_STATE}, the code and status this case already answered.
 *
 * <p>Split out of bare {@link IllegalStateException}, which in this service also carried an
 * internal-invariant guard (the PO sequence exceeding the 8-character code space). That is a
 * server-side defect and stays untyped so the platform advice answers a correlated 500.
 */
public class PurchaseOrderStateConflictException extends RuntimeException {

    public PurchaseOrderStateConflictException(String message) {
        super(message);
    }
}
