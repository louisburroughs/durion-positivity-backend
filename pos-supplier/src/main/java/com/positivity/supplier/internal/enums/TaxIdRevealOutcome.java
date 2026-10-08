package com.positivity.supplier.internal.enums;

/** What a vendor tax-registration reveal produced, as stored on its audit row (#2621). */
public enum TaxIdRevealOutcome {
    /** The number was decrypted and returned. */
    REVEALED,
    /** The stored ciphertext could not be decrypted; nothing was returned. */
    UNREADABLE
}
