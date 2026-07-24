package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.CostingMethodConfig;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import com.positivity.inventory.internal.repository.CostingMethodConfigRepository;
import java.util.Optional;
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
 * <p>The catalog replica ({@code ext_product}) carries no category, so with the
 * default {@link NoOpSkuCategoryProvider} the SKU_CATEGORY step always falls
 * through — the row is stored but unresolvable until a catalog contract
 * extension registers a {@code @Primary} {@link SkuCategoryProvider}. This
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
        Optional<CostingMethod> bySku = configRepository
                .findByScopeTypeAndScopeValueAndActiveTrue(CostingScopeType.SKU, stockItemId)
                .map(CostingMethodConfig::getMethod);
        if (bySku.isPresent()) {
            return bySku.get();
        }

        Optional<CostingMethod> byCategory = skuCategoryProvider
                .categoryOf(stockItemId)
                .flatMap(category -> configRepository.findByScopeTypeAndScopeValueAndActiveTrue(
                        CostingScopeType.SKU_CATEGORY, category))
                .map(CostingMethodConfig::getMethod);
        if (byCategory.isPresent()) {
            return byCategory.get();
        }

        return configRepository
                .findByScopeTypeAndScopeValueIsNullAndActiveTrue(CostingScopeType.DEFAULT)
                .map(CostingMethodConfig::getMethod)
                .orElse(defaultMethod);
    }
}
