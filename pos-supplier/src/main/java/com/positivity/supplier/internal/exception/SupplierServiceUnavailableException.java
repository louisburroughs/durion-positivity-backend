package com.positivity.supplier.internal.exception;

/**
 * A dependency this request needs could not answer (CAP:550 #2623): pos-tax's registration-scheme list, read before
 * a vendor's tax registration is stored. 503 {@code SERVICE_UNAVAILABLE} per ADR-0017; the request wrote nothing and
 * may be retried as sent. The message names the dependency and the status only, never a payload (ADR-0072).
 */
public class SupplierServiceUnavailableException extends RuntimeException {

    /** The one code of this exception. */
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    public SupplierServiceUnavailableException(String message) {
        super(message);
    }

    public SupplierServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public String getCode() {
        return SERVICE_UNAVAILABLE;
    }
}
