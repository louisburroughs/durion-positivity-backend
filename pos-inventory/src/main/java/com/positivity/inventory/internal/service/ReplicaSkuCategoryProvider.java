package com.positivity.inventory.internal.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The real {@link SkuCategoryProvider}, answering from the {@code ext_product} replica by way of
 * {@link ReplicaSkuCategoryLookup} (#1514).
 *
 * <p>This is the catalog contract extension {@link SkuCategoryProvider}'s javadoc has been waiting
 * for: {@code catalog.product.updated} now carries the product's category, the replica stores it,
 * and SKU_CATEGORY-scoped sourcing (H1) and costing config rows — stored but skipped ever since —
 * start resolving.
 *
 * <p><strong>Why this bean is switchable.</strong> Making the category step resolve is not a purely
 * additive change for costing: an active {@code SKU_CATEGORY} {@code costing_method_config} row
 * created while resolution was dead has never been reached, so the SKUs it covers have been costing
 * at DEFAULT with a live average. Turning resolution on makes the next ledger posting for such a
 * SKU resolve the configured method instead — a mid-life method change without the
 * {@code cost_method_change_log} row and revaluation cut-over that
 * {@code CostingMethodConfigServiceImpl} requires of a deliberate switch, and timed per SKU by
 * whenever pos-catalog next republishes it. {@code pos.inventory.sku-category.resolve-from-replica}
 * defaults to true because a deployment with no such rows (the expected case — none are seeded) has
 * nothing to protect; setting it to false hands {@link NoOpSkuCategoryProvider} the SPI again and
 * restores the pre-#1514 fall-through, which is the lever an operator needs if a costing method
 * moves unexpectedly. Before enabling this on an environment that has authored SKU_CATEGORY costing
 * rows, deactivate or migrate them deliberately.
 *
 * <p>The switch covers only this SPI. {@link SkuCategoryLookup}, which the putaway rule matcher
 * reads, stays registered either way — it answers a question nothing asked before #1514.
 *
 * <p>{@code categoryOf} answers with the category NAME, not the id, because that is what the
 * existing config rows are authored and matched against. That name is a snapshot taken when the
 * product fact was last published, and pos-catalog publishes product facts on product mutations —
 * <em>not</em> when a {@code Category} row is renamed. After a rename the replica keeps the old
 * name until each affected product is republished, so a rename must be paired with a product
 * replay. {@link SkuCategoryLookup} exposes {@code categoryId}, which is the rename-stable key, and
 * is what new matching should use.
 */
@Primary
@Component
@ConditionalOnProperty(
        prefix = "pos.inventory.sku-category",
        name = "resolve-from-replica",
        havingValue = "true",
        matchIfMissing = true)
public class ReplicaSkuCategoryProvider implements SkuCategoryProvider {

    private final SkuCategoryLookup skuCategoryLookup;

    public ReplicaSkuCategoryProvider(SkuCategoryLookup skuCategoryLookup) {
        this.skuCategoryLookup = skuCategoryLookup;
    }

    @Override
    public @NonNull Optional<String> categoryOf(@NonNull String stockItemId) {
        return skuCategoryLookup.categoryRefOf(stockItemId).map(SkuCategoryLookup.SkuCategoryRef::categoryName);
    }

    @Override
    public @NonNull Map<String, String> categoryOfAll(@NonNull Collection<String> stockItemIds) {
        Map<String, SkuCategoryLookup.SkuCategoryRef> refs = skuCategoryLookup.categoryRefOfAll(stockItemIds);
        if (refs.isEmpty()) {
            return Map.of();
        }
        Map<String, String> categories = new HashMap<>(refs.size());
        refs.forEach((stockItemId, ref) -> {
            // A product classified only by subcategory has no category name to match a
            // SKU_CATEGORY row on; it is absent rather than mapped to null.
            if (ref.categoryName() != null) {
                categories.put(stockItemId, ref.categoryName());
            }
        });
        return Map.copyOf(categories);
    }
}
