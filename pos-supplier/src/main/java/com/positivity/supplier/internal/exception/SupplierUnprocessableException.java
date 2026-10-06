package com.positivity.supplier.internal.exception;

/**
 * A well-formed request that names something it cannot use (#2516): a profile naming a vendor that
 * does not exist in the caller's tenant, or an inactive vendor on create. 422 per ADR-0017 — the
 * addressed resource exists; what the body refers to does not qualify.
 */
public class SupplierUnprocessableException extends RuntimeException {

    /** The profile's {@code vendorId} names no vendor of the caller's tenant. */
    public static final String VENDOR_NOT_FOUND = SupplierNotFoundException.VENDOR_NOT_FOUND;

    /** A new profile names an {@code INACTIVE} vendor. */
    public static final String VENDOR_INACTIVE = "SUPPLIER_VENDOR_INACTIVE";

    private final String code;

    public SupplierUnprocessableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
