package com.positivity.accounting.internal.enums;

/** Why a register's float was relocated (#2571; AW32): both reasons post the same reclass. */
public enum RegisterFloatRelocationReason {
    /** The register was set up under the wrong location. */
    ENTERED_IN_ERROR,
    /** The drawer physically moved to another location. */
    MOVED,
    /**
     * Written by the reversal of a go-live or Change float entry made before a move: the reversed amount moves
     * from the entry's location to the register's current one. Never accepted from a caller.
     */
    REVERSAL_FOLLOW_UP
}
