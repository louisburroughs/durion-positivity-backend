package com.positivity.accounting.internal.billintake.enums;

/** Where a stored bill file is in its own life (#2518; ADR-0070 Decision 8). */
public enum BillSourceFileState {
    /** Received and kept apart: not scanned or not yet inspected. Never served. */
    QUARANTINED,
    /** Scanned clean and inspected: the original, served to people who may read bills. */
    STORED,
    /** Refused (type, malware, scan failure, inspection); never served. */
    REJECTED,
    /** The bytes and derived pages are gone; this row keeps the metadata. */
    PURGED
}
