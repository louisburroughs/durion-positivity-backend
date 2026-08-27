package com.positivity.inventory.internal.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Reads a SKU's full catalog classification out of the {@code ext_product} replica (#1514).
 *
 * <p>Deliberately separate from {@link SkuCategoryProvider}. That SPI answers one narrow question —
 * "what string do SKU_CATEGORY-scoped sourcing and costing config rows match this SKU on" — and its
 * shape (an {@code Optional<String>} of the category NAME) is fixed by those existing callers. Rule
 * matching for category-based putaway needs more than that: it walks SKU → SUBCATEGORY → CATEGORY
 * and matches on ids, because {@code Batteries} is a subcategory of {@code Electrical System} and a
 * category-only key cannot express the containment the narrower level carries. Widening the SPI to
 * serve both would change a contract other callers depend on; this is the second question asked as
 * its own interface, answered by the same bean and the same replica row.
 */
public interface SkuCategoryLookup {

    /**
     * The SKU's replicated classification, or empty when the replica has never heard of the SKU,
     * the stock item id is not a product id, or the product carries no category at all.
     */
    @NonNull
    Optional<SkuCategoryRef> categoryRefOf(@NonNull String stockItemId);

    /**
     * Batch variant for matchers resolving many SKUs at once. SKUs with no resolvable
     * classification are absent from the map rather than mapped to null.
     */
    default @NonNull Map<String, SkuCategoryRef> categoryRefOfAll(@NonNull Collection<String> stockItemIds) {
        if (stockItemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SkuCategoryRef> refs = new HashMap<>();
        for (String stockItemId : stockItemIds) {
            categoryRefOf(stockItemId).ifPresent(ref -> refs.put(stockItemId, ref));
        }
        return Map.copyOf(refs);
    }

    /**
     * A SKU's catalog classification as replicated from {@code catalog.product.updated}.
     *
     * <p>Ids and names travel together on purpose: rules match on the id, which survives a rename,
     * while operators read and author against the name.
     *
     * @param categoryId catalog category id; null when the product carries only a subcategory
     * @param categoryName category name snapshot at the time of the last fact
     * @param subcategoryId catalog subcategory id; null when the product has no subcategory
     * @param subcategoryName subcategory name snapshot
     */
    record SkuCategoryRef(
            @Nullable UUID categoryId,
            @Nullable String categoryName,
            @Nullable UUID subcategoryId,
            @Nullable String subcategoryName) {

        /** True when neither level resolved — the caller has nothing to match on. */
        public boolean isEmpty() {
            return categoryId == null && categoryName == null && subcategoryId == null && subcategoryName == null;
        }
    }
}
