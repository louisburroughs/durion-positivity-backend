package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.CostingMethodConfig;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import com.positivity.inventory.internal.repository.CostingMethodConfigRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link CostingMethod} for a SKU at posting time (odoo-parity J1,
 * issue #1048; ADR-0048).
 *
 * <p><strong>Resolution precedence:</strong> active SKU config for the stock
 * item → active SKU_CATEGORY config for the SKU's category → active DEFAULT
 * config → deployment default {@code pos.inventory.valuation.default-method}
 * (itself defaulting to AVERAGE).
 *
 * <p>The SKU_CATEGORY step is <strong>inert by default</strong>. #1514
 * replicated the catalog category onto {@code ext_product}, but
 * {@link ReplicaSkuCategoryProvider} only becomes the {@code @Primary}
 * {@link SkuCategoryProvider} when
 * {@code pos.inventory.sku-category.resolve-from-replica} is enabled, and that
 * defaults to false; until then {@link NoOpSkuCategoryProvider} serves the SPI,
 * so this step resolves nothing and every SKU falls through to DEFAULT. With
 * the flag on it falls through only for a SKU whose category the replica cannot
 * resolve. Enabling it changes the method matching SKUs resolve at their next
 * posting — audit it first with
 * {@code GET /v1/inventory/valuation/methods/sku-category-impact} (#1535). This
 * mirrors the H1 sourcing-strategy resolution exactly and reuses the same
 * {@link SkuCategoryProvider} bean.
 */
@Component
public class CostingMethodResolver {

    private final CostingMethodConfigRepository configRepository;
    private final SkuCategoryProvider skuCategoryProvider;
    private final CostingMethod defaultMethod;

    public CostingMethodResolver(
            CostingMethodConfigRepository configRepository,
            SkuCategoryProvider skuCategoryProvider,
            @Value("${pos.inventory.valuation.default-method:AVERAGE}") CostingMethod defaultMethod) {
        this.configRepository = configRepository;
        this.skuCategoryProvider = skuCategoryProvider;
        this.defaultMethod = defaultMethod;
    }

    /** The deployment default method ({@code pos.inventory.valuation.default-method}). */
    public @NonNull CostingMethod defaultMethod() {
        return defaultMethod;
    }

    /** Resolves the effective costing method for one SKU. */
    public @NonNull CostingMethod resolve(@NonNull String stockItemId) {
        return resolveAll(Set.of(stockItemId)).get(stockItemId);
    }

    /** Resolves the effective costing method for many SKUs in one repository round-trip per scope. */
    public @NonNull Map<String, CostingMethod> resolveAll(@NonNull Set<String> stockItemIds) {
        if (stockItemIds.isEmpty()) {
            return Map.of();
        }

        Map<String, CostingMethod> resolved = new HashMap<>(stockItemIds.size());

        configRepository
                .findByScopeTypeAndScopeValueInAndActiveTrue(CostingScopeType.SKU, stockItemIds)
                .forEach(config -> resolved.put(config.getScopeValue(), config.getMethod()));

        Set<String> unresolved = new HashSet<>(stockItemIds);
        unresolved.removeAll(resolved.keySet());

        if (!unresolved.isEmpty()) {
            Map<String, String> categoryBySku = skuCategoryProvider.categoryOfAll(unresolved);
            if (!categoryBySku.isEmpty()) {
                Set<String> categories = new HashSet<>(categoryBySku.values());
                Map<String, CostingMethod> categoryMethods = new HashMap<>(categories.size());
                configRepository
                        .findByScopeTypeAndScopeValueInAndActiveTrue(CostingScopeType.SKU_CATEGORY, categories)
                        .forEach(config -> categoryMethods.put(config.getScopeValue(), config.getMethod()));
                categoryBySku.forEach((skuId, category) -> {
                    CostingMethod method = categoryMethods.get(category);
                    if (method != null) {
                        resolved.putIfAbsent(skuId, method);
                    }
                });
            }
        }

        Set<String> stillUnresolved = new HashSet<>(stockItemIds);
        stillUnresolved.removeAll(resolved.keySet());
        if (!stillUnresolved.isEmpty()) {
            CostingMethod fallback = configRepository
                    .findByScopeTypeAndScopeValueIsNullAndActiveTrue(CostingScopeType.DEFAULT)
                    .map(CostingMethodConfig::getMethod)
                    .orElse(defaultMethod);
            stillUnresolved.forEach(skuId -> resolved.putIfAbsent(skuId, fallback));
        }
        return Map.copyOf(resolved);
    }
}
