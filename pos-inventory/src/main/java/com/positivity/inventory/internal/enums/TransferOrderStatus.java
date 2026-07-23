package com.positivity.inventory.internal.enums;

/**
 * Lifecycle of a cross-site transfer order (odoo-parity C1, issue #1035; spec §4).
 *
 * <p>Status machine: {@code DRAFT → APPROVED(optional, config) → DISPATCHED →
 * PARTIALLY_RECEIVED → RECEIVED | SHORT_CLOSED | CANCELLED}. The approval step only exists when
 * {@code pos.inventory.transfer.approval-required=true} (decision D-8; default off, DRAFT
 * dispatches directly). Cancellation is allowed only before dispatch; post-dispatch
 * discrepancies resolve via short-close (parity-C3 — {@link #SHORT_CLOSED} is declared now so
 * the persistence CHECK constraint never needs another migration).
 */
public enum TransferOrderStatus {

    /** Created, not yet approved/dispatched; freely editable and cancellable. */
    DRAFT,

    /** Approved for dispatch (only reachable when the approval flag is on). */
    APPROVED,

    /** Dispatched from the source; goods are in transit to the destination. */
    DISPATCHED,

    /** Some, but not all, dispatched quantity has been received at the destination. */
    PARTIALLY_RECEIVED,

    /** All dispatched quantity received at the destination; terminal. */
    RECEIVED,

    /** Closed with undelivered remainder via loss/return disposition (parity-C3); terminal. */
    SHORT_CLOSED,

    /** Cancelled before dispatch; no stock moved; terminal. */
    CANCELLED;

    /** True while the order may still be cancelled (nothing has physically moved). */
    public boolean cancellable() {
        return this == DRAFT || this == APPROVED;
    }

    /** True while dispatched stock remains attributable to this order (receipts still allowed). */
    public boolean receivable() {
        return this == DISPATCHED || this == PARTIALLY_RECEIVED;
    }

    /** True when the order is terminal — no further transitions. */
    public boolean terminal() {
        return this == RECEIVED || this == SHORT_CLOSED || this == CANCELLED;
    }
}
