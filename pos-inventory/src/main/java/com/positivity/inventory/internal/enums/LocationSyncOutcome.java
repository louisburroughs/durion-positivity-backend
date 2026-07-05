package com.positivity.inventory.internal.enums;

/**
 * Outcome of a location sync run or of an individual roster record
 * (CAP-214 #40).
 */
public enum LocationSyncOutcome {
    OK,
    PARTIAL,
    FAILED,
    INVALID_PAYLOAD
}
