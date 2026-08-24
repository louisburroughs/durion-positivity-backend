package com.positivity.marketing.internal.enums;

/**
 * What kind of catalog item an {@code ext_catalog} row replicates (#1306).
 *
 * <p>Narrower than {@code CatalogFocusRef.Kind}, which is the grammar a marketer writes: {@code
 * sku:} and {@code category:} name attributes of a product rather than aggregates pos-catalog
 * publishes facts about, so both resolve against {@link #PRODUCT} rows.
 */
public enum CatalogItemKind {
    PRODUCT,
    SERVICE
}
