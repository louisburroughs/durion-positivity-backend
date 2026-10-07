package com.positivity.accounting.internal.enums;

/** What changed a register's float (#2511; AW16, AW17). */
public enum RegisterFloatChangeKind {
    /** The once-only go-live float: Dr 1080 / Cr 3900. */
    GO_LIVE,
    /** A Change float against a bank account. */
    CHANGE,
    /** The reversal of a GO_LIVE or CHANGE entry; the amount is re-derived from the entries still standing. */
    REVERSAL
}
