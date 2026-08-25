package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Thrown when putaway is requested for a receipt whose stock never passed through staging.
 *
 * <p>Putaway moves stock out of the staging location into storage; a receipt that placed its
 * stock directly at a location other than staging has nothing staged to put away. This is a
 * modeling refusal, not a data consistency error: the caller should look at the receipt's actual
 * location rather than retry or reconcile.
 */
public class ReceiptNotStagedException extends PutawayValidationException {
    public static final String ERROR_CODE = "RECEIPT_NOT_STAGED";

    private final UUID receiptId;
    private final UUID actualLocationId;
    private final UUID stagingLocationId;

    public ReceiptNotStagedException(UUID receiptId, UUID actualLocationId, UUID stagingLocationId) {
        super(
                ERROR_CODE,
                String.format(
                        "Goods receipt %s cannot be put away: its stock is at location %s, not the staging "
                                + "location %s. Putaway only applies to stock that went through staging.",
                        receiptId, actualLocationId, stagingLocationId));
        this.receiptId = receiptId;
        this.actualLocationId = actualLocationId;
        this.stagingLocationId = stagingLocationId;
    }

    public UUID getReceiptId() {
        return receiptId;
    }

    public UUID getActualLocationId() {
        return actualLocationId;
    }

    public UUID getStagingLocationId() {
        return stagingLocationId;
    }
}
