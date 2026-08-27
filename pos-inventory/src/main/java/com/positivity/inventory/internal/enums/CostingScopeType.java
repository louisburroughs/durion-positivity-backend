package com.positivity.inventory.internal.enums;

/**
 * Configuration scope of one {@code sku_cost_method_config} row (odoo-parity
 * J1, issue #1048; ADR-0048). Resolution precedence is SKU → SKU_CATEGORY →
 * DEFAULT → deployment default {@code pos.inventory.valuation.default-method}.
 *
 * <p>Mirrors {@link SourcingScopeType} (odoo-parity H1) but keyed for costing.
 */
public enum CostingScopeType {

    /** Per-SKU override; {@code scopeValue} carries the stock item id. */
    SKU,

    /**
     * Per-SKU-category override; {@code scopeValue} carries the category name,
     * matched exactly (and case-sensitively) after trimming.
     *
     * <p>The catalog replica ({@code ext_product}) has carried the product's
     * category since #1514, but resolution through this scope is gated by
     * {@code pos.inventory.sku-category.resolve-from-replica}, which defaults
     * to <strong>false</strong> — so by default these rows are still stored and
     * skipped, and resolution falls through to DEFAULT. Enabling the flag makes
     * them resolve for the first time, which changes the costing method of
     * matching SKUs at their next ledger posting; audit it first with
     * {@code GET /v1/inventory/valuation/methods/sku-category-impact} (#1535).
     * Mirrors the H1 sourcing behaviour.
     */
    SKU_CATEGORY,

    /** Platform-wide default; {@code scopeValue} is null. */
    DEFAULT
}
