package com.positivity.inventory.internal.service;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * No-category {@link SkuCategoryProvider} fallback (odoo-parity H1, issue #1037):
 * resolves nothing, so SKU_CATEGORY-scoped sourcing and costing config falls
 * through to SITE, then DEFAULT.
 *
 * <p>No longer the effective default — {@link ReplicaSkuCategoryProvider} is the
 * {@code @Primary} bean since #1514 replicated the category onto
 * {@code ext_product}. This bean is the reachable fallback behind that: setting
 * {@code pos.inventory.sku-category.resolve-from-replica=false} withdraws the
 * replica-backed bean and leaves this one serving the SPI, which is the lever for
 * an operator who needs the pre-#1514 fall-through back (see
 * {@link ReplicaSkuCategoryProvider} for why a costing deployment might).
 */
@Component
public class NoOpSkuCategoryProvider implements SkuCategoryProvider {

    @Override
    public @NonNull Optional<String> categoryOf(@NonNull String stockItemId) {
        return Optional.empty();
    }
}
