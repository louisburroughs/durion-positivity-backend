package com.positivity.accounting.internal.billintake.enums;

/** What a person has done with one field of a draft's read-back. */
public enum BillFieldState {
    /** Read from the document or the fact; not yet looked at. */
    READ,
    /** Must be checked before the draft is confirmed. */
    CHECK_THIS,
    /** A person looked and kept the value. */
    ACCEPTED,
    /** A person changed the value. */
    CORRECTED
}
