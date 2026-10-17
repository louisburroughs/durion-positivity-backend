package com.positivity.order.internal.exception;

import java.io.Serial;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * A revise or approve built on a version of the purchase order that is no longer current (CAP:550, #2681): the
 * request's {@code expectedVersionNumber} is not the stored {@code versionNumber}. ADR-0017 §2 makes a version
 * collision a {@code 409}; this one maps to {@code PURCHASE_ORDER_VERSION_CONFLICT}, distinct from the lifecycle
 * refusal {@code PURCHASE_ORDER_INVALID_STATE}, because the remedy differs: re-read the order and apply the change to
 * what is there now, rather than wait for a status change. Nothing is written and no fact is published.
 */
public class PurchaseOrderVersionConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID purchaseOrderId;
    private final int expectedVersionNumber;
    private final int currentVersionNumber;

    public PurchaseOrderVersionConflictException(
            @NonNull UUID purchaseOrderId, @NonNull String poNumber, int expectedVersionNumber, int currentVersionNumber) {
        super("Purchase order " + poNumber + " is at version " + currentVersionNumber + "; this change was built on"
                + " version " + expectedVersionNumber + ". Re-read the order and apply the change to the current"
                + " version.");
        this.purchaseOrderId = purchaseOrderId;
        this.expectedVersionNumber = expectedVersionNumber;
        this.currentVersionNumber = currentVersionNumber;
    }

    public @NonNull UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public int getExpectedVersionNumber() {
        return expectedVersionNumber;
    }

    public int getCurrentVersionNumber() {
        return currentVersionNumber;
    }
}
