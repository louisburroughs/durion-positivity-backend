package com.positivity.supplier.internal.enums;

/**
 * State of a remit-to change request (#2516, SPEC §4.9): {@code PENDING → APPROVED | REJECTED}, both
 * terminal.
 */
public enum RemitChangeStatus {
    /** Waiting for someone other than the requester to approve or reject it. */
    PENDING,
    /** Applied to the vendor by a second person. */
    APPROVED,
    /** Refused by a second person; the vendor is unchanged. */
    REJECTED
}
