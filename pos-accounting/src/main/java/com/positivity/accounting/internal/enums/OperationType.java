package com.positivity.accounting.internal.enums;

/**
 * Operation type for statement line calculations.
 */
public enum OperationType {
    /** Add this account balance to the line total */
    SUM,

    /** Subtract this account balance from the line total (e.g., contra assets) */
    SUBTRACT,

    /**
     * Negate the account balance before adding (e.g., show expenses as positive)
     */
    NEGATE
}
