package com.positivity.inventory.internal.exception;

/**
 * Thrown when an outbound posting for a LOT-tracked product references a lot number that does
 * not exist in the lot master (odoo-parity E2, issue #1042; spec §6 E2).
 *
 * <p>Maps to a deterministic 422 with code {@code LOT_UNKNOWN}. Outbound flows never
 * find-or-create lots — stock can only leave a lot that inbound capture (E1) registered.
 */
public class LotUnknownException extends RuntimeException {

    public static final String ERROR_CODE = "LOT_UNKNOWN";

    private final String stockItemId;
    private final String lotNumber;

    public LotUnknownException(String stockItemId, String lotNumber) {
        super("LOT_UNKNOWN: no lot '" + lotNumber + "' exists for stock item " + stockItemId
                + "; outbound postings may only reference lots registered by inbound receipt");
        this.stockItemId = stockItemId;
        this.lotNumber = lotNumber;
    }

    public String getStockItemId() {
        return stockItemId;
    }

    public String getLotNumber() {
        return lotNumber;
    }
}
