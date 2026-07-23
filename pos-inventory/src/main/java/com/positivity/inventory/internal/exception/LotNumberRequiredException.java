package com.positivity.inventory.internal.exception;

/**
 * Thrown when a stock movement for a LOT-tracked product carries no lot number: receipts since
 * odoo-parity E1 (issue #1038; spec §6 E1), outbound flows — pick confirmation, consumption,
 * transfer dispatch, scrap, cross-dock, returns — since E2 (issue #1042; spec §6 E2).
 *
 * <p>Maps to a deterministic 422 with code {@code LOT_NUMBER_REQUIRED}; a missing lot on a
 * tracked product must never degrade to an untracked posting.
 */
public class LotNumberRequiredException extends RuntimeException {

    public static final String ERROR_CODE = "LOT_NUMBER_REQUIRED";

    private final String stockItemId;

    public LotNumberRequiredException(String stockItemId) {
        super("LOT_NUMBER_REQUIRED: stock item " + stockItemId
                + " is LOT-tracked; this movement must carry a lotNumber");
        this.stockItemId = stockItemId;
    }

    public String getStockItemId() {
        return stockItemId;
    }
}
