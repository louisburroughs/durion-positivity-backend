package com.positivity.inventory.internal.exception;

/**
 * Thrown when a goods receipt would push the received total past the purchase order's open
 * balance and the caller does not hold {@code inventory:goods_receipt:override}.
 *
 * <p>Maps to a deterministic 422 with code {@code OVER_RECEIPT_NOT_PERMITTED} — the request is
 * well-formed, but posting it would over-receive the order without the authority to do so.
 */
public class OverReceiptNotPermittedException extends RuntimeException {

    public static final String ERROR_CODE = "OVER_RECEIPT_NOT_PERMITTED";

    public OverReceiptNotPermittedException(String message) {
        super(message);
    }
}
