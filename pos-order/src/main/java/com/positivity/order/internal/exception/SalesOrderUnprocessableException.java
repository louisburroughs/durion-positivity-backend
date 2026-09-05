package com.positivity.order.internal.exception;

/**
 * A structurally valid sales-order request that a domain rule refuses on its merits: an empty
 * cart at quote or checkout, a SKU whose price cannot be resolved, a WORKORDER source link
 * without a customer on the cart, a quote attempted on a workorder-linked order, a
 * serial/lot-tracked line whose captured identifiers do not match its quantity, a new cart on a
 * terminal whose register session is mid-close, or a source document that does not resolve in
 * the replica (issue #1730). Maps to {@code ORDER_UNPROCESSABLE}.
 *
 * <p>ADR-0017 §2, as reworded on 2026-09-04, is what puts all of these at {@code 422} rather than
 * splitting them: its {@code 409} list is closed and covers only a collision with the *target*
 * resource's identity, version or lifecycle status. Everything above is refused either by an
 * attribute of the target other than its status, or by the state of a *referenced* resource —
 * "resource state alone never selects 409". Those genuine target-lifecycle collisions do exist in
 * this module; they travel as {@code OrderNotEditableException} and
 * {@code InvalidOrderStateTransitionException}, which {@code OrderStateExceptionHandler} answers
 * with a 409 module-wide.
 *
 * <p>Distinct from {@link SalesOrderRequestValidationException} (400 — the payload is malformed on
 * its face). Both were bare {@link IllegalStateException} until #1730, which is why this module's
 * four advices could not agree on one status for them.
 */
public class SalesOrderUnprocessableException extends RuntimeException {

    public SalesOrderUnprocessableException(String message) {
        super(message);
    }
}
