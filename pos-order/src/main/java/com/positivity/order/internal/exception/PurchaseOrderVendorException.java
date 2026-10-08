package com.positivity.order.internal.exception;

import java.io.Serial;
import org.jspecify.annotations.NonNull;

/**
 * A purchase order names a vendor that cannot take new business (CAP:550 S24, #2517; SPEC-accounting-workspace §7.3,
 * G15): the vendor is in pos-order's copy of pos-supplier's vendor master and {@code INACTIVE} ({@code
 * VENDOR_INACTIVE}). It answers 422: the request is understood and the order may exist, but the vendor it names cannot
 * be ordered from. A vendor the copy does not hold is not refused here: it may only not have replicated yet, which
 * {@code SupplierVendorGuard} answers 503 {@code VENDOR_REPLICATION_PENDING} (ADR-0017 §1). The message never echoes
 * another tenant's identifier.
 */
public class PurchaseOrderVendorException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The refusal and its stable code. */
    public enum Code {
        /** The vendor is in the copy and {@code INACTIVE}: it takes no new purchase order. */
        VENDOR_INACTIVE
    }

    private final Code code;

    private PurchaseOrderVendorException(@NonNull Code code, @NonNull String message) {
        super(message);
        this.code = code;
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
