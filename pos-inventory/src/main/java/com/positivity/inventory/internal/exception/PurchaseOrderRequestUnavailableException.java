package com.positivity.inventory.internal.exception;

/**
 * The purchase-order request could not be queued because no outbox writer is wired (CAP:550 S36, #2525, PROPOSED 8):
 * answered 422 {@code PURCHASE_ORDER_REQUEST_UNAVAILABLE} and the conversion rolls back, so no suggestion is ever
 * marked CONVERTED with no order requested (pos-order's {@code TRANSMISSION_UNAVAILABLE} precedent).
 */
public class PurchaseOrderRequestUnavailableException extends RuntimeException {

    public static final String CODE = "PURCHASE_ORDER_REQUEST_UNAVAILABLE";

    public PurchaseOrderRequestUnavailableException() {
        super("Purchase-order requests cannot be queued in this deployment (no outbox writer is wired);"
                + " the suggestions stay ACCEPTED");
    }
}
