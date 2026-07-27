package com.positivity.inventory.internal.enums;

/**
 * Configuration scope of one {@code sourcing_strategy_config} row (odoo-parity
 * H1, issue #1037). Resolution precedence is SKU_CATEGORY → SITE → DEFAULT →
 * platform default FIFO.
 */
public enum SourcingScopeType {

    /**
     * Per-SKU-category override; {@code scopeValue} carries the category
     * string. The catalog replica ({@code ext_product}) does not carry a
     * category yet, so category-scoped rows are stored but unresolvable
     * until a catalog extension lights them up — resolution skips to SITE.
     */
    SKU_CATEGORY,

    /** Per-site override; {@code scopeValue} carries the site UUID as text. */
    SITE,

    /** Platform-wide default; {@code scopeValue} is null. */
    DEFAULT
}
