package com.positivity.order.internal.entity;

public enum SalesOrderStatus {
    DRAFT,
    QUOTED,
    /** Checkout accepted; awaiting settlement confirmation (parity plan stories C1/C3). */
    PENDING_PAYMENT,
    COMPLETED,
    VOIDED,
    CANCEL_REQUESTED,
    WORKORDER_CANCELLED,
    PAYMENT_REVERSED,
    CANCELLED,
    CANCEL_FAILED_WORKEXEC,
    CANCEL_FAILED_BILLING,
    CANCEL_REQUIRES_MANUAL_REVIEW
}
