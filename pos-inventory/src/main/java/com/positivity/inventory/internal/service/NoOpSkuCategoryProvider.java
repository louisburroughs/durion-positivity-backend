package com.positivity.inventory.internal.service;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * No-category {@link SkuCategoryProvider} fallback (odoo-parity H1, issue #1037):
 * resolves nothing, so SKU_CATEGORY-scoped sourcing and costing config falls
 * through to SITE, then DEFAULT.
 *
 * <p><strong>This is the default implementation.</strong>
 * {@link ReplicaSkuCategoryProvider} takes over as the {@code @Primary} bean
 * only when {@code pos.inventory.sku-category.resolve-from-replica} is enabled,
 * and that defaults to false — so unless an operator has deliberately turned it
 * on, this bean is what serves the SPI and the SKU_CATEGORY step of both costing
 * and sourcing resolves nothing. Setting the flag back to false withdraws the
 * replica-backed bean and returns the SPI here, which is the lever for an
 * operator who needs the pre-#1514 fall-through back (see
 * {@link ReplicaSkuCategoryProvider} for why a costing deployment might).
 */
@Component
public class NoOpSkuCategoryProvider implements SkuCategoryProvider {

    @Override
    public @NonNull Optional<String> categoryOf(@NonNull String stockItemId) {
        return Optional.empty();
    }
}
