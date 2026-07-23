package com.positivity.inventory.internal.enums;

/**
 * Removal/sourcing ordering strategies supported by the sourcing strategy
 * engine (odoo-parity H1, issue #1037; Odoo's {@code _get_removal_strategy}
 * analog).
 *
 * <p>Non-goals (documented in the spec): {@code LIFO} and least-packages have
 * no POS scenario and are intentionally absent.
 */
public enum SourcingStrategy {

    /**
     * First-in-first-out: candidate locations ordered by the earliest
     * on-hand receipt ({@code GOODS_RECEIPT}/{@code TRANSFER_IN} ledger
     * timestamp) of the SKU at each location, earliest first. This is a
     * documented approximation — per-location earliest-receipt timestamp,
     * not per-unit aging.
     */
    FIFO,

    /**
     * First-expired-first-out: candidate locations ordered by the earliest
     * lot expiry of the SKU at each location. Lots do not exist yet
     * (odoo-parity E1); until a real {@code LotExpiryProvider} reports
     * expiry data for a SKU, FEFO falls back to {@link #FIFO} ordering.
     */
    FEFO,

    /**
     * Topology proximity: candidate locations ordered by hop distance to a
     * reference location in the replicated location tree
     * ({@code ext_storage_location} parent links plus typed
     * {@code ext_location_parent} edges), nearest first. Without a
     * reference location the strategy falls back to {@link #FIFO}.
     */
    PROXIMITY,

    /** Most available stock ({@code onHand - allocated}) first. */
    HIGHEST_STOCK
}
