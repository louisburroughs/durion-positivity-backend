package com.positivity.order.internal.entity;

/**
 * Lifecycle of a return order (odoo-parity stories F1/F2, issues #1086/#1087, spec R5.1–R5.3).
 *
 * <p>F1 creation lands in {@code RETURN_REQUESTED}, or {@code PENDING_APPROVAL} when the refund
 * exceeds the approval threshold (then {@code RETURN_REQUESTED} on approve, {@code REJECTED} on
 * reject). F2's saga advances {@code RETURN_REQUESTED → REFUND_ISSUED → COMPLETED}, parking at
 * {@code REFUND_FAILED} (before any stock movement, retryable). Restock is event-driven and
 * fire-and-forget (pos-inventory consumes {@code order.order.returned}), so there is no synchronous
 * stock-return step in this state machine. {@code CANCELLED} is reserved for future return
 * cancellation; like {@code REJECTED} it does not reserve quantity against the cap.
 */
public enum ReturnOrderStatus {
    PENDING_APPROVAL,
    RETURN_REQUESTED,
    REFUND_ISSUED,
    COMPLETED,
    REFUND_FAILED,
    REJECTED,
    CANCELLED
}
