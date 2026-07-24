package com.positivity.inventory.internal.exception;

/**
 * Thrown when an inbound posting enumerates a serial number that is already {@code IN_STOCK} for
 * the stock item (odoo-parity E4, issue #1050): the same physical unit cannot be received twice.
 *
 * <p>Maps to a deterministic 422 with code {@code SERIAL_ALREADY_IN_STOCK}, matching the other
 * serial-invariant violations ({@code SERIAL_COUNT_MISMATCH}, {@code SERIAL_NOT_AVAILABLE}) rather
 * than surfacing as a generic conflict.
 */
public class SerialAlreadyInStockException extends RuntimeException {

    public static final String ERROR_CODE = "SERIAL_ALREADY_IN_STOCK";

    private final String stockItemId;
    private final String serialNumber;

    public SerialAlreadyInStockException(String stockItemId, String serialNumber) {
        super("SERIAL_ALREADY_IN_STOCK: serial number " + serialNumber + " of stock item " + stockItemId
                + " is already in stock and cannot be received again");
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
