package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Thrown when a transfer dispatch/receive request exceeds a line's remaining quantity
 * (odoo-parity C2, issue #1036): dispatch beyond requested, or receive beyond
 * {@code dispatched − already received}. Maps to a deterministic 422 whose code names the
 * violated bound.
 */
public class TransferQuantityExceededException extends RuntimeException {

    /** Dispatch quantity exceeds the line's requested quantity. */
    public static final String DISPATCH_EXCEEDS_REQUESTED = "TRANSFER_DISPATCH_EXCEEDS_REQUESTED";

    /** Received quantity exceeds the line's un-received dispatched remainder. */
    public static final String RECEIVE_EXCEEDS_DISPATCHED = "TRANSFER_RECEIVE_EXCEEDS_DISPATCHED";

    private final String errorCode;

    private TransferQuantityExceededException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static TransferQuantityExceededException dispatchExceedsRequested(
            UUID lineId, String sku, int quantity, int requested) {
        return new TransferQuantityExceededException(
                DISPATCH_EXCEEDS_REQUESTED,
                "Cannot dispatch " + quantity + " of " + sku + " on line " + lineId + ": only " + requested
                        + " requested");
    }

    public static TransferQuantityExceededException receiveExceedsDispatched(
            UUID lineId, String sku, int quantity, int remaining) {
        return new TransferQuantityExceededException(
                RECEIVE_EXCEEDS_DISPATCHED,
                "Cannot receive " + quantity + " of " + sku + " on line " + lineId + ": only " + remaining
                        + " remains in transit (dispatched minus already received)");
    }

    public String getErrorCode() {
        return errorCode;
    }
}
