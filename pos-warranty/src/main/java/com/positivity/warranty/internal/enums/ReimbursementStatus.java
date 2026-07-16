package com.positivity.warranty.internal.enums;

/**
 * Vendor-reimbursement child lifecycle (PRD §3.7):
 * NOT_SUBMITTED → SUBMITTED → APPROVED | PARTIALLY_APPROVED | DENIED, then CREDIT_RECEIVED or
 * WRITTEN_OFF. DEALER-type providers skip reimbursement entirely (NOT_APPLICABLE).
 */
public enum ReimbursementStatus {
    NOT_SUBMITTED,
    SUBMITTED,
    APPROVED,
    PARTIALLY_APPROVED,
    DENIED,
    CREDIT_RECEIVED,
    WRITTEN_OFF,
    NOT_APPLICABLE
}
