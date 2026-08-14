package com.positivity.supplier.internal.adapter.ediwheelb;

/**
 * The vendor answered, but with something that is not a usable B2.1 stock report — an unparseable
 * body, or one carrying a document-level vendor error code.
 *
 * <p>Distinct from a rejected line, which is quarantined while the snapshot continues. This fails
 * the whole snapshot, and it must: publishing a vendor rejection as an empty stock report would
 * tell every consumer that an entire market has nothing in stock.
 */
public class StockReportDecodeException extends RuntimeException {

    public StockReportDecodeException(String message) {
        super(message);
    }

    public StockReportDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
