package com.positivity.supplier.internal.adapter.ediwheela2;

/**
 * An A2.5 quote document could not be read.
 *
 * <p>Unlike an unreadable order response (ADR-0052 §3), this is not an ambiguity: nothing was
 * committed by asking. The caller turns it into {@code SUPPLIER_UNAVAILABLE} — we asked, we did not
 * get a usable answer — and the caller degrades rather than failing its own flow.
 */
public class StockInquiryDecodeException extends RuntimeException {

    public StockInquiryDecodeException(String message) {
        super(message);
    }

    public StockInquiryDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
