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
 * <p>The catalog replica ({@code ext_product}) does not carry a category
 * today, so the default {@link NoOpSkuCategoryProvider} resolves nothing and
 * SKU_CATEGORY-scoped config rows are stored but skipped — resolution falls
 * through to SITE, then DEFAULT. A later catalog contract extension registers
 * a real {@code @Primary} implementation to light them up.
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
