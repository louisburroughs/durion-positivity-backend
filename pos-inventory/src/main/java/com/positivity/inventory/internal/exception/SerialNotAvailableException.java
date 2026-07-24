package com.positivity.inventory.internal.exception;

/**
 * Thrown when an outbound posting names a serial number that is not currently {@code IN_STOCK}
 * for the stock item (odoo-parity E4, issue #1050): an unknown serial, or a double-issue of a
 * serial already {@code ISSUED}/{@code SCRAPPED}/{@code RETURNED}.
 *
 * <p>Maps to a deterministic 422 with code {@code SERIAL_NOT_AVAILABLE}.
 */
public class SerialNotAvailableException extends RuntimeException {

    public static final String ERROR_CODE = "SERIAL_NOT_AVAILABLE";

    private final String stockItemId;
    private final String serialNumber;

    public SerialNotAvailableException(String stockItemId, String serialNumber) {
        super("SERIAL_NOT_AVAILABLE: serial number " + serialNumber + " of stock item " + stockItemId
                + " is not in stock; it is unknown or has already left stock");
        this.stockItemId = stockItemId;
        this.serialNumber = serialNumber;
    }

    public String getStockItemId() {
        return stockItemId;
    }

    public String getSerialNumber() {
        return serialNumber;
    }
}
