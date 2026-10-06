package com.positivity.supplier.internal.exception;

/**
 * A domain rule refuses the caller even though they hold the permission (#2516): the person who
 * requested a remit-to change may not approve it. 403 per ADR-0017, carrying its own code so a client
 * can tell it from a missing permission.
 */
public class SupplierForbiddenException extends RuntimeException {

    /** The approver is the requester; a second person must approve (SPEC §4.9). */
    public static final String VENDOR_REMIT_SELF_APPROVAL = "SUPPLIER_VENDOR_REMIT_SELF_APPROVAL";

    private final String code;

    public SupplierForbiddenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
