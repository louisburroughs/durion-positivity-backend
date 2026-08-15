package com.positivity.supplier.internal.adapter.ediwheela2;

/**
 * A canonical inquiry could not be expressed as an A2.5 document.
 *
 * <p>Always a configuration or programming fault on our side — a missing agency code, an article
 * with no identifier the norm can carry — never a vendor failure, so the caller reports
 * {@code CONFIGURATION_ERROR} and makes no network call.
 */
public class StockInquiryEncodeException extends RuntimeException {

    public StockInquiryEncodeException(String message) {
        super(message);
    }

    public StockInquiryEncodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
