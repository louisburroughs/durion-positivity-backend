package com.positivity.inventory.internal.enums;

/**
 * Inventory costing method resolved per SKU at posting time (odoo-parity J1,
 * issue #1048; ADR-0048 "costing method is configurable"). The resolved method
 * selects the {@code CostingStrategy} that computes the method-derived
 * {@code unitCost} stamped on every on-hand-affecting ledger entry.
 *
 * <p>Resolution precedence (see {@code CostingMethodResolver}): per-SKU config
 * row → per-SKU-category config row (reachable only when
 * {@code pos.inventory.sku-category.resolve-from-replica} is enabled; off by
 * default) → DEFAULT config row → deployment default
 * {@code pos.inventory.valuation.default-method} (which itself defaults to
 * {@link #AVERAGE}).
 */
public enum CostingMethod {

    /**
     * Standard costing: {@code unitCost} is the SKU's configured standard
     * price; receipts do not change it (purchase-price-variance handling is
     * out of J1 scope). When no standard price is configured the strategy
     * falls back to the latest receipt cost, then to {@code null}
     * (uncosted). Setting/correcting the standard price is the revaluation
     * workflow (plan story J4), not J1.
     */
    STANDARD,

    /**
     * Weighted-average costing (AVCO, Odoo {@code _update_standard_price}
     * mechanics): each receipt recomputes the running weighted average per
     * SKU; issues stamp the current average and leave it unchanged. This is
     * the v1 default.
     */
    AVERAGE;

    // NOTE (ADR-0048): FIFO is a planned v2 method behind the same
    // {@code CostingStrategy} interface (per-receipt remaining-qty/remaining-value
    // layer stack, Odoo v19 {@code remaining_qty} design). It is intentionally
    // NOT a value here in v1 — the config check constraint and resolver enumerate
    // only STANDARD and AVERAGE. FIFO lands with its own strategy impl, layer
    // side-state, and check-constraint widening; the interface and per-entry
    // stamping contract designed here accommodate it without rework.
}
