package com.positivity.inventory.internal.enums;

/**
 * Scope of a location sync log entry (CAP-214 #40): a run-level summary
 * or an individual roster record that failed to apply.
 */
public enum LocationSyncLogScope {
    RUN,
    RECORD
}
