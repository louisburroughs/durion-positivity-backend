package com.positivity.supplier.internal.adapter.ediwheelc1;

/**
 * A canonical order could not be expressed in the bound norm.
 *
 * <p>Always a defect on this side — a missing agency code on a commercial account, a line with
 * no article identity — and always raised <strong>before any network I/O</strong>. That timing is
 * the point: an encode failure leaves the transmission attempt in {@code PENDING}, where
 * ADR-0052 §2 permits a later re-dispatch once the configuration is fixed, rather than in the
 * ambiguous post-send state that costs an operator a manual reconciliation.
 */
public class OrderEncodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OrderEncodeException(String message) {
        super(message);
    }

    public OrderEncodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
