package com.positivity.accounting.enums;

/**
 * AP Payment Status enum.
 * 
 * Lifecycle: PENDING_APPROVAL → APPROVED → SCHEDULED → PAID (or CANCELLED)
 */
public enum APPaymentStatus {
    PENDING_APPROVAL,
    APPROVED,
    SCHEDULED,
    PAID,
    CANCELLED
}
