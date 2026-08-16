package com.positivity.order.internal.enums;

/**
 * Lifecycle of a purchase order (CAP-320, ADR-0049 §2).
 *
 * <p>The two middle states are the ones that matter to anyone outside this module:
 * {@code APPROVED} and {@code PARTIALLY_RECEIVED} are what count as incoming supply. The
 * authoritative statement of that rule lives on the published fact rather than here, so consumers
 * do not each decide it for themselves.
 */
public enum PurchaseOrderStatus {
    DRAFT,
    APPROVED,
    PARTIALLY_RECEIVED,
    FULLY_RECEIVED,
    CLOSED,
    CANCELLED
}
