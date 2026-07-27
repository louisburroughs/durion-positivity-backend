package com.positivity.inventory.internal.exception;

/**
 * Thrown when an on-hand-affecting posting for a SERIAL-tracked stock item is not accompanied by
 * exactly {@code |changeInQuantity|} distinct serial numbers (odoo-parity E4, issue #1050; spec
 * §6 — "serial postings enumerate units: quantity must equal serial count").
 *
 * <p>Maps to a deterministic 422 with code {@code SERIAL_COUNT_MISMATCH}. The invariant is
 * absolute: a serialized receipt of N units MUST enumerate N serials, and a serialized issue of
 * N units MUST name N in-stock serials; a blank, duplicate, or miscounted set is rejected and the
 * whole posting rolls back.
 */
public class SerialCountMismatchException extends RuntimeException {

    public static final String ERROR_CODE = "SERIAL_COUNT_MISMATCH";

    private final String stockItemId;

    public SerialCountMismatchException(String stockItemId, int requiredCount, int suppliedCount) {
        super("SERIAL_COUNT_MISMATCH: SERIAL-tracked stock item " + stockItemId + " requires exactly " + requiredCount
                + " distinct serial number(s) for this posting but " + suppliedCount + " were supplied");
        this.stockItemId = stockItemId;
    }

    public String getStockItemId() {
        return stockItemId;
    }
}
