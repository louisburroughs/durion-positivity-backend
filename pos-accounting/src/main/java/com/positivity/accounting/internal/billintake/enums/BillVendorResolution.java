package com.positivity.accounting.internal.billintake.enums;

/** How a draft's vendor was found in accounting's copy of the vendor master (ruling 3 of #2518). */
public enum BillVendorResolution {
    /** Exactly one vendor matched the reading. */
    READ,
    /** Several vendors matched, or only a registration's last four did: a person picks one. */
    CANDIDATES,
    /** No vendor in the copy matched. Confirmation answers 422 VENDOR_NOT_FOUND until one is picked. */
    NOT_SET_UP,
    /** A person picked the vendor. */
    RESOLVED
}
