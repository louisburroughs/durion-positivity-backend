package com.positivity.inventory.internal.enums;

import org.jspecify.annotations.NonNull;

/**
 * Per-event-type negative-stock policy matrix (odoo-parity K1, issue #1027).
 *
 * <p>Classifies every {@link InventoryLedgerEventType} by what happens when a
 * posting would take the {@code (stockItemId, locationId)} on-hand balance
 * below zero. The matrix is enforced exclusively inside the single ledger
 * posting funnel ({@code LedgerPostingServiceImpl}); a DB-level check is
 * impractical against an append-only ledger.
 *
 * <p>See {@code pos-inventory/docs/negative-stock-policy.md} for the full
 * matrix table with per-row rationale.
 */
public enum NegativeStockPolicy {

    /**
     * Posting below zero is always rejected ({@code InsufficientStockException},
     * {@code INSUFFICIENT_STOCK}). Physical outbound flows cannot take more
     * stock than exists.
     */
    BLOCKED,

    /**
     * Posting below zero is rejected unless the caller passes an explicit
     * negative-stock override flag after its own
     * {@code inventory:adjustment:override} permission check
     * ({@code NEGATIVE_STOCK_OVERRIDE_REQUIRED} otherwise).
     */
    BLOCKED_OVERRIDABLE,

    /**
     * Posting may drive on-hand to exactly zero but never below
     * ({@code NEGATIVE_STOCK_FLOOR_VIOLATION}); counts set reality, so they
     * can zero stock but not invent negative stock. Not overridable.
     */
    FLOOR_AT_ZERO,

    /**
     * No negative-stock constraint. Inbound flows and paired-location moves
     * (e.g. the PUTAWAY source decrement) post freely; neutral/ATP-only types
     * never touch on-hand and are equally unconstrained.
     */
    UNCONSTRAINED;

    /**
     * Returns the negative-stock policy for the given ledger event type.
     *
     * @param eventType the ledger event type being posted
     * @return the policy row of the matrix that applies to the event type
     */
    public static @NonNull NegativeStockPolicy forEventType(@NonNull InventoryLedgerEventType eventType) {
        return switch (eventType) {
            case GOODS_ISSUE, WORKORDER_CONSUMPTION, TRANSFER_OUT -> BLOCKED;
            case SCRAP_OUT -> BLOCKED_OVERRIDABLE;
            case ADJUSTMENT_OUT, COUNT_VARIANCE_OUT, ADJUST_CYCLE_COUNT -> FLOOR_AT_ZERO;
            default -> UNCONSTRAINED;
        };
    }
}
