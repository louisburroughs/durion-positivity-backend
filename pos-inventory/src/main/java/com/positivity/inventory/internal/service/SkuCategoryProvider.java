package com.positivity.inventory.internal.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * SPI hook that resolves a SKU's catalog category for SKU_CATEGORY-scoped
 * sourcing-strategy configuration (odoo-parity H1, issue #1037).
 *
 * <p>The catalog contract extension this SPI was waiting for landed in #1514:
 * {@code catalog.product.updated} now carries the product's category and the
 * {@code ext_product} replica stores it. Resolving it, however, is switchable:
 * {@link ReplicaSkuCategoryProvider} is the {@code @Primary} implementation
 * <strong>when {@code pos.inventory.sku-category.resolve-from-replica} is
 * enabled</strong>, and that flag defaults to false. By default the bean is not
 * registered at all and {@link NoOpSkuCategoryProvider} is what serves this SPI
 * — category resolution finds nothing and lookups fall through to SITE, then
 * DEFAULT, exactly as before #1514. SKU_CATEGORY-scoped config rows therefore
 * remain stored and skipped until an operator enables the flag deliberately;
 * {@code GET /v1/inventory/valuation/methods/sku-category-impact} reports what
 * doing so would change (#1535).
 *
 * <p>The answer is the category NAME, because that is what SKU_CATEGORY-scoped
 * config rows are authored and matched against. Callers that need the category
 * and subcategory ids — putaway rule matching does, since containment is
 * expressed one level down — ask {@link SkuCategoryLookup} instead.
 */
public interface SkuCategoryProvider {

    /** The SKU's category, or empty when no category source exists. */
    @NonNull
    Optional<String> categoryOf(@NonNull String stockItemId);

    /** Batch variant for callers that need categories for many SKUs. */
    default @NonNull Map<String, String> categoryOfAll(@NonNull Collection<String> stockItemIds) {
        if (stockItemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> categories = new HashMap<>();
        for (String stockItemId : stockItemIds) {
            categoryOf(stockItemId).ifPresent(category -> categories.put(stockItemId, category));
        }
        return Map.copyOf(categories);
    }
}
