package com.positivity.inventory.internal.service;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Default no-category {@link SkuCategoryProvider}: {@code ext_product} carries
 * no category field, so SKU_CATEGORY-scoped sourcing config is unresolvable
 * for now (odoo-parity H1, issue #1037). A catalog contract extension replaces
 * this with a {@code @Primary} bean.
 */
@Component
public class NoOpSkuCategoryProvider implements SkuCategoryProvider {

    @Override
    public @NonNull Optional<String> categoryOf(@NonNull String stockItemId) {
        return Optional.empty();
    }
}
