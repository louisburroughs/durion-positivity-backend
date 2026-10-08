package com.positivity.order.internal.exception;

import java.io.Serial;
import org.jspecify.annotations.NonNull;

/**
 * A purchase order names a vendor that cannot take new business (CAP:550 S24, #2517; SPEC-accounting-workspace §7.3,
 * G15): the vendor is not in pos-order's copy of pos-supplier's vendor master ({@code VENDOR_NOT_FOUND}), or it is
 * there and {@code INACTIVE} ({@code VENDOR_INACTIVE}). Both answer 422: the request is understood and the order may
 * exist, but the vendor it names cannot be ordered from. The message never echoes another tenant's identifier.
 */
public class PurchaseOrderVendorException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The two refusals and their stable codes. */
    public enum Code {
        /** The vendor id is not in this tenant's vendor copy (not seeded yet, or not a pos-supplier vendor). */
        VENDOR_NOT_FOUND,
        /** The vendor is in the copy and {@code INACTIVE}: it takes no new purchase order. */
        VENDOR_INACTIVE
    }

    private final Code code;

    private PurchaseOrderVendorException(@NonNull Code code, @NonNull String message) {
        super(message);
        this.code = code;
    }

    /** The vendor is not in this tenant's vendor copy. */
    public static @NonNull PurchaseOrderVendorException notFound() {
        return new PurchaseOrderVendorException(
                Code.VENDOR_NOT_FOUND,
                "The vendor is not in this shop's vendor master. Choose a vendor set up in pos-supplier, or wait"
                        + " until its record has reached ordering.");
    }

    /** The vendor is inactive. */
    public static @NonNull PurchaseOrderVendorException inactive(@NonNull String vendorNumber) {
        return new PurchaseOrderVendorException(
                Code.VENDOR_INACTIVE,
                "Vendor " + vendorNumber + " is inactive and takes no new purchase orders. Reactivate it in the vendor"
                        + " master, or revise the order to another vendor.");
    }

    public @NonNull Code getCode() {
        return code;
    }
}
