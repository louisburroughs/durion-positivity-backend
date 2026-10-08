package com.positivity.supplier.internal.vendor.service.model;

/** What a vendor tax-registration reveal produced (#2621). */
public enum TaxIdRevealOutcome {
    /** The number was decrypted and returned. */
    REVEALED,
    /** The stored ciphertext could not be decrypted; nothing was returned (500 SUPPLIER_VENDOR_TAX_ID_UNREADABLE). */
    UNREADABLE
}
