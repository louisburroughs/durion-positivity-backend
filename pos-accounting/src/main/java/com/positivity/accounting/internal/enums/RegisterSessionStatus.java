package com.positivity.accounting.internal.enums;

/** A replicated pos-order register session's state (#2571, #2573): OPEN until its close fact, then CLOSED for good. */
public enum RegisterSessionStatus {
    /** Opened in pos-order (OPEN or CLOSING there) and not yet closed. */
    OPEN,
    /** Closed; a session never reopens. */
    CLOSED
}
