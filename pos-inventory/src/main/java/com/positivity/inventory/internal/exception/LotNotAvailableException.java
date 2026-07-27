package com.positivity.inventory.internal.exception;

/**
 * Thrown when an outbound posting for a LOT-tracked product references a lot whose status
 * blocks it from leaving stock (odoo-parity E2, issue #1042; spec §6 E2): outbound flows
 * require an {@code ACTIVE} lot — {@code QUARANTINED}, {@code RECALLED}, and {@code CONSUMED}
 * lots are all rejected.
 *
 * <p>Maps to a deterministic 422 with code {@code LOT_NOT_AVAILABLE}.
 */
public class LotNotAvailableException extends RuntimeException {

    public static final String ERROR_CODE = "LOT_NOT_AVAILABLE";

    private final String stockItemId;
    private final String lotNumber;
    private final String lotStatus;

    public LotNotAvailableException(String stockItemId, String lotNumber, String lotStatus) {
        super("LOT_NOT_AVAILABLE: lot '" + lotNumber + "' of stock item " + stockItemId + " is " + lotStatus
                + "; only ACTIVE lots may be picked, consumed, transferred, or scrapped");
        this.stockItemId = stockItemId;
        this.lotNumber = lotNumber;
        this.lotStatus = lotStatus;
    }

    public String getStockItemId() {
        return stockItemId;
    }

    public String getLotNumber() {
        return lotNumber;
    }

    public String getLotStatus() {
        return lotStatus;
    }
}
