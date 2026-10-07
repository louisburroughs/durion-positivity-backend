package com.positivity.accounting.internal.enums;

/** What changed a register's float (#2511; AW16, AW17; relocation #2571, AW32). */
public enum RegisterFloatChangeKind {
    /** The once-only go-live float: Dr 1080 / Cr 3900. */
    GO_LIVE,
    /** A Change float against a bank account. */
    CHANGE,
    /** The reversal of a GO_LIVE or CHANGE entry; the amount is re-derived from the entries still standing. */
    REVERSAL,
    /**
     * The register moved to another location: Dr 1080 at the destination / Cr 1080 at the origin for the float,
     * which is unchanged (nothing posts for a zero float). Never reversed: a wrong move is moved again.
     */
    RELOCATION
}
