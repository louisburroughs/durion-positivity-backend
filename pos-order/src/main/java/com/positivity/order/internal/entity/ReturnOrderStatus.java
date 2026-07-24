package com.positivity.order.internal.entity;

/**
 * Lifecycle of a return order (odoo-parity stories F1/F2, issues #1086/#1087, spec R5.1–R5.3).
 *
 * <p>F1 creation lands in {@code RETURN_REQUESTED}, or {@code PENDING_APPROVAL} when the refund
 * exceeds the approval threshold (then {@code RETURN_REQUESTED} on approve, {@code REJECTED} on
 * reject). F2's saga advances {@code RETURN_REQUESTED → REFUND_ISSUED → STOCK_RETURNED →
 * COMPLETED}, parking at {@code REFUND_FAILED} (before any stock movement, retryable) or
 * {@code STOCK_FAILED} on error.
 */
public enum ReturnOrderStatus {
    PENDING_APPROVAL,
    RETURN_REQUESTED,
    REFUND_ISSUED,
    STOCK_RETURNED,
    COMPLETED,
    REFUND_FAILED,
    STOCK_FAILED,
    REJECTED,
    CANCELLED
}
