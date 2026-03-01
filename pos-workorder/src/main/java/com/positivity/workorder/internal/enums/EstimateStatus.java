package com.positivity.workorder.internal.enums;

public enum EstimateStatus {
    DRAFT,
    /** Estimate submitted and awaiting internal review / approval. */
    PENDING_APPROVAL,
    /** Estimate has been presented to the customer but not yet accepted. */
    OPEN,
    /** Awaiting customer decision after being sent. */
    PENDING_CUSTOMER,
    APPROVED,
    DECLINED,
    EXPIRED,
    /** Work has been scheduled based on an approved estimate. */
    SCHEDULED,
    /** Invoice has been generated from the estimate. */
    INVOICED,
    CANCELLED,
    /** Estimate closed and archived; no further action expected. */
    ARCHIVED
}
