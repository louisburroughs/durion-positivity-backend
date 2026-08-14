package com.positivity.supplier.internal.adapter.ediwheelc1;

/**
 * A vendor order or order-status document could not be read as the norm it was bound to.
 *
 * <p>Deliberately distinct from a vendor <em>rejection</em>: a rejection is the vendor answering
 * on the merits, while this is our inability to understand the answer. For an order create the
 * difference decides an operator's day — a rejection is a business outcome to act on, an
 * undecodable response is an ambiguous outcome that must reconcile through order status rather
 * than be treated as "not ordered" (ADR-0052 §3).
 */
public class OrderDecodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OrderDecodeException(String message) {
        super(message);
    }

    public OrderDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
