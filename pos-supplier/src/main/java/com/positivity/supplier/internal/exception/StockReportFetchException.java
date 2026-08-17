package com.positivity.supplier.internal.exception;

import java.io.Serial;

/**
 * A vendor stock snapshot could not be obtained (CAP-322, #1349).
 *
 * <p>Thrown by {@code SupplierStockReportPort} rather than returned as an empty snapshot. A snapshot
 * is a statement about what a vendor holds, and an empty one says "nothing anywhere" — which is a
 * claim, not an absence of one. A consumer acting on it would report every article as unavailable
 * because a request timed out.
 */
public class StockReportFetchException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public StockReportFetchException(String message) {
        super(message);
    }

    public StockReportFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
