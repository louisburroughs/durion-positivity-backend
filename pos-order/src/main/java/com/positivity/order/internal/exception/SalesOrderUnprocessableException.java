package com.positivity.order.internal.exception;

/**
 * A structurally valid sales-order request that a domain rule refuses on its merits: an empty
 * cart at quote or checkout, a SKU whose price cannot be resolved, a WORKORDER source link
 * without a customer on the cart, a quote attempted on a workorder-linked order, or a
 * serial/lot-tracked line whose captured identifiers do not match its quantity (issue #1730).
 * ADR-0017 §2 makes that a {@code 422}; maps to {@code ORDER_UNPROCESSABLE}.
 *
 * <p>Distinct from {@link SalesOrderRequestValidationException} (400 — the payload is malformed
 * on its face) and from {@link SalesOrderStateConflictException} (409 — another resource's state
 * refuses the request). All three were bare {@link IllegalStateException} until #1730, which is
 * why this module's four advices could not agree on one status for it.
 */
public class SalesOrderUnprocessableException extends RuntimeException {

    public SalesOrderUnprocessableException(String message) {
        super(message);
    }
}
