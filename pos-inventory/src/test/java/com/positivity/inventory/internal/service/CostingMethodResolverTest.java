package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.CostingMethodConfig;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import com.positivity.inventory.internal.repository.CostingMethodConfigRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Costing-method resolution precedence (odoo-parity J1, issue #1048; ADR-0048). */
@ExtendWith(MockitoExtension.class)
@DisplayName("CostingMethodResolver")
class CostingMethodResolverTest {

    @Mock
    private CostingMethodConfigRepository configRepository;

    private static final String SKU = "SKU-1";

    private CostingMethodResolver resolver(SkuCategoryProvider categoryProvider) {
        return new CostingMethodResolver(configRepository, categoryProvider, CostingMethod.AVERAGE);
    }

    private CostingMethodConfig config(CostingMethod method) {
        return CostingMethodConfig.builder().method(method).build();
    }

    @Test
    @DisplayName("no config anywhere: falls through to the deployment default")
    void noConfig_returnsDeploymentDefault() {
        when(configRepository.findByScopeTypeAndScopeValueAndActiveTrue(CostingScopeType.SKU, SKU))
                .thenReturn(Optional.empty());
        when(configRepository.findByScopeTypeAndScopeValueIsNullAndActiveTrue(CostingScopeType.DEFAULT))
                .thenReturn(Optional.empty());

        assertThat(resolver(new NoOpSkuCategoryProvider()).resolve(SKU)).isEqualTo(CostingMethod.AVERAGE);
    }

    @Test
    @DisplayName("DEFAULT config row wins over the deployment default")
    void defaultConfigRow_winsOverDeploymentDefault() {
        when(configRepository.findByScopeTypeAndScopeValueAndActiveTrue(CostingScopeType.SKU, SKU))
                .thenReturn(Optional.empty());
        when(configRepository.findByScopeTypeAndScopeValueIsNullAndActiveTrue(CostingScopeType.DEFAULT))
                .thenReturn(Optional.of(config(CostingMethod.STANDARD)));

        assertThat(resolver(new NoOpSkuCategoryProvider()).resolve(SKU)).isEqualTo(CostingMethod.STANDARD);
    }

    @Test
    @DisplayName("per-SKU config wins over everything")
    void skuConfig_winsOverAll() {
        when(configRepository.findByScopeTypeAndScopeValueAndActiveTrue(CostingScopeType.SKU, SKU))
                .thenReturn(Optional.of(config(CostingMethod.STANDARD)));

        assertThat(resolver(new NoOpSkuCategoryProvider()).resolve(SKU)).isEqualTo(CostingMethod.STANDARD);
    }

    @Test
    @DisplayName("category-scoped config is unresolvable with the NoOp provider: skips to DEFAULT")
    void categoryUnresolvable_skipsToDefault() {
        when(configRepository.findByScopeTypeAndScopeValueAndActiveTrue(CostingScopeType.SKU, SKU))
                .thenReturn(Optional.empty());
        when(configRepository.findByScopeTypeAndScopeValueIsNullAndActiveTrue(CostingScopeType.DEFAULT))
                .thenReturn(Optional.of(config(CostingMethod.AVERAGE)));

        // NoOp provider returns no category, so SKU_CATEGORY rows are never consulted.
        assertThat(resolver(new NoOpSkuCategoryProvider()).resolve(SKU)).isEqualTo(CostingMethod.AVERAGE);
    }

    @Test
    @DisplayName("when a category IS resolvable, its config row is consulted between SKU and DEFAULT")
    void categoryResolvable_categoryConfigConsulted() {
        when(configRepository.findByScopeTypeAndScopeValueAndActiveTrue(CostingScopeType.SKU, SKU))
                .thenReturn(Optional.empty());
        when(configRepository.findByScopeTypeAndScopeValueAndActiveTrue(CostingScopeType.SKU_CATEGORY, "CAT-1"))
                .thenReturn(Optional.of(config(CostingMethod.STANDARD)));

        SkuCategoryProvider provider = stockItemId -> Optional.of("CAT-1");
        assertThat(resolver(provider).resolve(SKU)).isEqualTo(CostingMethod.STANDARD);
    }
}
