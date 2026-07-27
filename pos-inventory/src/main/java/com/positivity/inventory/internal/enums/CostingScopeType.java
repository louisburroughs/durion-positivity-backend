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
     * Per-SKU-category override; {@code scopeValue} carries the category
     * string. The catalog replica ({@code ext_product}) does not carry a
     * category yet, so category-scoped rows are stored but unresolvable until
     * a catalog contract extension lights them up — resolution skips to
     * DEFAULT. Mirrors the H1 sourcing behaviour.
     */
    SKU_CATEGORY,

    /** Platform-wide default; {@code scopeValue} is null. */
    DEFAULT
}
