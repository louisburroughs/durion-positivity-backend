package com.positivity.inventory.internal.exception;

/**
 * Thrown when a receipt posting for a LOT-tracked product carries no lot number
 * (odoo-parity E1, issue #1038; spec §6 E1).
 *
 * <p>Maps to a deterministic 422 with code {@code LOT_NUMBER_REQUIRED}; a missing lot on a
 * tracked product must never degrade to an untracked posting.
 */
public class LotNumberRequiredException extends RuntimeException {

    public static final String ERROR_CODE = "LOT_NUMBER_REQUIRED";

    private final String stockItemId;

    public LotNumberRequiredException(String stockItemId) {
        super("LOT_NUMBER_REQUIRED: stock item " + stockItemId
                + " is LOT-tracked; receipt lines must carry a lotNumber");
        this.stockItemId = stockItemId;
    }

    public String getStockItemId() {
        return stockItemId;
    }
}
