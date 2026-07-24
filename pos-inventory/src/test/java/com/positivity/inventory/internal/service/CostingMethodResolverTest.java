package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.CostingMethodConfig;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import com.positivity.inventory.internal.repository.CostingMethodConfigRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    @Mock
    private SkuCategoryProvider skuCategoryProvider;

    private static final String SKU_A = "SKU-A";
    private static final String SKU_B = "SKU-B";
    private static final String SKU_C = "SKU-C";
    private static final String SKU_D = "SKU-D";

    private CostingMethodResolver resolver(CostingMethod defaultMethod) {
        return new CostingMethodResolver(configRepository, skuCategoryProvider, defaultMethod);
    }

    private CostingMethodConfig config(CostingScopeType scopeType, String scopeValue, CostingMethod method) {
        return CostingMethodConfig.builder()
                .scopeType(scopeType)
                .scopeValue(scopeValue)
                .method(method)
                .active(true)
                .build();
    }

    @Test
    @DisplayName("resolveAll applies SKU > category > DEFAULT precedence in one batched pass")
    void resolveAll_appliesSkuCategoryDefaultPrecedence() {
        CostingMethodResolver resolver = resolver(CostingMethod.AVERAGE);
        Set<String> stockItems = Set.of(SKU_A, SKU_B, SKU_C, SKU_D);

        when(configRepository.findByScopeTypeAndScopeValueInAndActiveTrue(CostingScopeType.SKU, stockItems))
                .thenReturn(List.of(config(CostingScopeType.SKU, SKU_A, CostingMethod.STANDARD)));
        when(skuCategoryProvider.categoryOfAll(Set.of(SKU_B, SKU_C, SKU_D)))
                .thenReturn(Map.of(SKU_B, "CAT-1", SKU_C, "CAT-2"));
        when(configRepository.findByScopeTypeAndScopeValueInAndActiveTrue(
                        CostingScopeType.SKU_CATEGORY, Set.of("CAT-1", "CAT-2")))
                .thenReturn(List.of(config(CostingScopeType.SKU_CATEGORY, "CAT-1", CostingMethod.AVERAGE)));
        when(configRepository.findByScopeTypeAndScopeValueIsNullAndActiveTrue(CostingScopeType.DEFAULT))
                .thenReturn(Optional.of(config(CostingScopeType.DEFAULT, null, CostingMethod.STANDARD)));

        Map<String, CostingMethod> resolved = resolver.resolveAll(stockItems);

        assertThat(resolved).containsEntry(SKU_A, CostingMethod.STANDARD);
        assertThat(resolved).containsEntry(SKU_B, CostingMethod.AVERAGE);
        assertThat(resolved).containsEntry(SKU_C, CostingMethod.STANDARD);
        assertThat(resolved).containsEntry(SKU_D, CostingMethod.STANDARD);
        assertThat(resolved).hasSize(4);

        verify(configRepository).findByScopeTypeAndScopeValueInAndActiveTrue(CostingScopeType.SKU, stockItems);
        verify(skuCategoryProvider).categoryOfAll(Set.of(SKU_B, SKU_C, SKU_D));
        verify(configRepository)
                .findByScopeTypeAndScopeValueInAndActiveTrue(CostingScopeType.SKU_CATEGORY, Set.of("CAT-1", "CAT-2"));
        verify(configRepository).findByScopeTypeAndScopeValueIsNullAndActiveTrue(CostingScopeType.DEFAULT);
        verify(configRepository, never()).findByScopeTypeAndScopeValueAndActiveTrue(any(), anyString());
        verify(skuCategoryProvider, never()).categoryOf(anyString());
    }

    @Test
    @DisplayName("resolveAll falls back to deployment default when DEFAULT row is absent")
    void resolveAll_noDefaultRow_usesDeploymentDefault() {
        CostingMethodResolver resolver = resolver(CostingMethod.STANDARD);

        when(configRepository.findByScopeTypeAndScopeValueInAndActiveTrue(CostingScopeType.SKU, Set.of(SKU_A)))
                .thenReturn(List.of());
        when(skuCategoryProvider.categoryOfAll(Set.of(SKU_A))).thenReturn(Map.of());
        when(configRepository.findByScopeTypeAndScopeValueIsNullAndActiveTrue(CostingScopeType.DEFAULT))
                .thenReturn(Optional.empty());

        Map<String, CostingMethod> resolved = resolver.resolveAll(Set.of(SKU_A));

        assertThat(resolved).containsExactly(Map.entry(SKU_A, CostingMethod.STANDARD));
        verify(configRepository, never())
                .findByScopeTypeAndScopeValueInAndActiveTrue(eq(CostingScopeType.SKU_CATEGORY), anyCollection());
        verify(configRepository, never()).findByScopeTypeAndScopeValueAndActiveTrue(any(), anyString());
    }

    @Test
    @DisplayName("resolve delegates to resolveAll path so lookup stays batched")
    void resolve_delegatesToResolveAllBatchedPath() {
        CostingMethodResolver resolver = resolver(CostingMethod.AVERAGE);

        when(configRepository.findByScopeTypeAndScopeValueInAndActiveTrue(CostingScopeType.SKU, Set.of(SKU_A)))
                .thenReturn(List.of(config(CostingScopeType.SKU, SKU_A, CostingMethod.STANDARD)));

        CostingMethod resolved = resolver.resolve(SKU_A);

        assertThat(resolved).isEqualTo(CostingMethod.STANDARD);
        verify(configRepository).findByScopeTypeAndScopeValueInAndActiveTrue(CostingScopeType.SKU, Set.of(SKU_A));
        verify(configRepository, never()).findByScopeTypeAndScopeValueAndActiveTrue(any(), anyString());
    }

    @Test
    @DisplayName("resolveAll with empty input returns empty and hits no collaborators")
    void resolveAll_emptyInput_returnsEmptyWithoutCalls() {
        CostingMethodResolver resolver = resolver(CostingMethod.AVERAGE);

        assertThat(resolver.resolveAll(Set.of())).isEmpty();
        verify(configRepository, never()).findByScopeTypeAndScopeValueInAndActiveTrue(any(), anyCollection());
        verify(configRepository, never()).findByScopeTypeAndScopeValueIsNullAndActiveTrue(any());
        verify(skuCategoryProvider, never()).categoryOfAll(anyCollection());
    }
}
