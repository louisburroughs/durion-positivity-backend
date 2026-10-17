package com.positivity.inventory.internal.enums;

/** Why a feed vendor cannot yet be a purchase candidate (CAP:550 S36, #2525). */
public enum UnmappedFeedVendorReason {
    /** No vendor feed mapping names the feed vendor. */
    UNMAPPED,
    /** A mapping exists, but the vendor it names is not in the local pos-supplier vendor copy. */
    VENDOR_NOT_IN_COPY,
    /** A mapping exists, but the vendor it names is INACTIVE. */
    VENDOR_INACTIVE
}
