package com.positivity.supplier.internal.exception;

import java.io.Serial;

/**
 * The vendor's view of an order could not be obtained (CAP-320, #1349).
 *
 * <p>Thrown by {@code SupplierOrderStatusPort}, and caught by the reconciler, which turns it into an
 * answer meaning "we still do not know". That distinction is load-bearing: order status is how an
 * ambiguous transmission is resolved (ADR-0052 §3), so "the vendor says it has no such order" and
 * "we could not ask the vendor" lead to opposite conclusions about whether an order was placed.
 * Collapsing them would let a reconciler cancel an order the vendor is already shipping.
 */
public class OrderStatusUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public OrderStatusUnavailableException(String message) {
        super(message);
    }

    public OrderStatusUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
