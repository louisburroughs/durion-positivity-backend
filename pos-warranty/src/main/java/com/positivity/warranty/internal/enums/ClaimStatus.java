package com.positivity.warranty.internal.enums;

/** Counter-explainable claim state machine (PRD §5); reimbursement and part return run child lifecycles. */
public enum ClaimStatus {
    DRAFT,
    SUBMITTED,
    IN_REVIEW,
    INFO_NEEDED,
    APPROVED,
    DENIED,
    SETTLED,
    CLOSED,
    CANCELLED
}
