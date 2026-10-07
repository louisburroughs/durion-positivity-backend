package com.positivity.accounting.internal.enums;

/**
 * Where a closed register session's drawer cash stands (CAP:550 S18, #2514; SPEC-accounting-workspace §4.5, AW10):
 * {@code UNDEPOSITED → DEPOSITED → UNDEPOSITED}, the way back only by reversing the deposit.
 */
public enum UndepositedSessionStatus {
    /** Waiting to be deposited: no standing deposit has taken it. */
    UNDEPOSITED,
    /** Taken whole by a standing deposit. */
    DEPOSITED
}
