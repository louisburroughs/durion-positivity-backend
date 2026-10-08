package com.positivity.accounting.internal.exception;

import java.io.Serial;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * 404 {@code VENDOR_NOT_FOUND}: the vendor read or command names a vendor that is not in accounting's copy of the
 * pos-supplier vendor master (CAP:550 S24, #2517). A new bill or payment naming one answers 422 instead ({@link
 * VendorBillException.Code#VENDOR_NOT_FOUND}).
 */
public class VendorNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The stable error code. */
    public static final String CODE = "VENDOR_NOT_FOUND";

    public VendorNotFoundException(@NonNull UUID vendorId) {
        super("Vendor " + vendorId + " is not in the vendor copy; it may not be set up in pos-supplier yet");
    }
}
