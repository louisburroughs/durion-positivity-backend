package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactResponse;
import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactRow;
import com.positivity.inventory.internal.entity.CostingMethodConfig;
import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.SkuCostState;
import com.positivity.inventory.internal.entity.SourcingStrategyConfig;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import com.positivity.inventory.internal.enums.SourcingScopeType;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.repository.CostingMethodConfigRepository;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import com.positivity.inventory.internal.repository.SourcingStrategyConfigRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The SKU_CATEGORY cut-over impact report (#1535).
 *
 * <p>The report exists to be read <em>before</em>
 * {@code pos.inventory.sku-category.resolve-from-replica} is enabled, so the
 * flag is left false in every test here except where a test says otherwise —
 * and {@link #impact_worksWhileResolveFromReplicaIsFalse()} is the one that
 * pins that this is not an accident.
 */
@DisplayName("SkuCategoryCutoverService impact report")
class SkuCategoryCutoverServiceImplTest {

    private static final UUID PRODUCT_A = UUID.fromString("018f0000-0000-7000-8000-0000000015a1");
    private static final UUID PRODUCT_B = UUID.fromString("018f0000-0000-7000-8000-0000000015a2");
    private static final UUID CATEGORY_ID = UUID.fromString("018f0000-0000-7000-8000-0000000015b1");
    private static final UUID CONFIG_ID = UUID.fromString("018f0000-0000-7000-8000-0000000015c1");
    private static final UUID SOURCING_CONFIG_ID = UUID.fromString("018f0000-0000-7000-8000-0000000015d1");
    private static final String CATEGORY = "Electrical System";

    private final CostingMethodConfigRepository configRepository = mock(CostingMethodConfigRepository.class);
    private final SourcingStrategyConfigRepository sourcingStrategyConfigRepository =
            mock(SourcingStrategyConfigRepository.class);
    private final ExtProductReplicaRepository extProductReplicaRepository = mock(ExtProductReplicaRepository.class);
    private final SkuCostStateRepository skuCostStateRepository = mock(SkuCostStateRepository.class);
    private final CostingMethodResolver costingMethodResolver = mock(CostingMethodResolver.class);

    @BeforeEach
    void defaultStubs() {
        when(costingMethodResolver.defaultMethod()).thenReturn(CostingMethod.AVERAGE);
        when(configRepository.findByScopeTypeAndActiveTrue(any())).thenReturn(List.of());
        when(configRepository.findByScopeTypeAndScopeValueIsNullAndActiveTrue(any()))
                .thenReturn(Optional.empty());
        when(configRepository.findByScopeTypeAndScopeValueInAndActiveTrue(any(), anyCollection()))
                .thenReturn(List.of());
        when(sourcingStrategyConfigRepository.findByScopeTypeAndActiveTrue(any()))
                .thenReturn(List.of());
        when(extProductReplicaRepository.findByTrimmedCategoryNameIn(anyCollection()))
                .thenReturn(List.of());
        when(skuCostStateRepository.findByStockItemIdIn(anyCollection())).thenReturn(List.of());
    }

    private SkuCategoryCutoverServiceImpl service(boolean resolveFromReplicaEnabled) {
        return new SkuCategoryCutoverServiceImpl(
                configRepository,
                sourcingStrategyConfigRepository,
                extProductReplicaRepository,
                skuCostStateRepository,
                costingMethodResolver,
                resolveFromReplicaEnabled);
    }

    private static CostingMethodConfig categoryConfig(String categoryName, CostingMethod method) {
        return CostingMethodConfig.builder()
                .configId(CONFIG_ID)
                .scopeType(CostingScopeType.SKU_CATEGORY)
                .scopeValue(categoryName)
                .method(method)
                .active(true)
                .build();
    }

    private static ExtProductReplica replica(UUID productId, String categoryName) {
        return ExtProductReplica.builder()
                .productId(productId)
                .categoryId(CATEGORY_ID)
                .categoryName(categoryName)
                .trackingLevel(ExtProductReplica.TRACKING_LEVEL_NONE)
                .build();
    }

    private void givenCategoryConfig(CostingMethodConfig... configs) {
        when(configRepository.findByScopeTypeAndActiveTrue(CostingScopeType.SKU_CATEGORY))
                .thenReturn(List.of(configs));
    }

    private void givenReplicas(ExtProductReplica... replicas) {
        when(extProductReplicaRepository.findByTrimmedCategoryNameIn(anyCollection()))
                .thenReturn(List.of(replicas));
    }

    @Test
    @DisplayName("with no active SKU_CATEGORY configs the report says there is nothing to do")
    void impact_withNoActiveSkuCategoryConfigs_reportsNothingToDo() {
        SkuCategoryImpactResponse report = service(false).impact();

        assertThat(report.getActiveSkuCategoryConfigCount()).isZero();
        assertThat(report.getEvaluatedSkuCount()).isZero();
        assertThat(report.getImpactedSkuCount()).isZero();
        assertThat(report.getImpactedSkuWithCostStateCount()).isZero();
        assertThat(report.getImpactedSkus()).isEmpty();
        assertThat(report.getImpactedSourcingSkus()).isEmpty();
        assertThat(report.getCategoriesWithNoReplicatedProducts()).isEmpty();
        assertThat(report.getDeploymentDefaultMethod()).isEqualTo(CostingMethod.AVERAGE);
    }

    @Test
    @DisplayName("reports the SKUs whose costing method would change")
    void impact_reportsSkusWhoseCostingMethodWouldChange() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.STANDARD));
        givenReplicas(replica(PRODUCT_A, CATEGORY), replica(PRODUCT_B, CATEGORY));

        SkuCategoryImpactResponse report = service(false).impact();

        assertThat(report.getActiveSkuCategoryConfigCount()).isEqualTo(1);
        assertThat(report.getEvaluatedSkuCount()).isEqualTo(2);
        assertThat(report.getImpactedSkuCount()).isEqualTo(2);
        assertThat(report.getImpactedSkus())
                .extracting(SkuCategoryImpactRow::getStockItemId)
                .containsExactlyInAnyOrder(PRODUCT_A.toString(), PRODUCT_B.toString());
        assertThat(report.getImpactedSkus()).allSatisfy(row -> {
            assertThat(row.getCurrentMethod()).isEqualTo(CostingMethod.AVERAGE);
            assertThat(row.getProjectedMethod()).isEqualTo(CostingMethod.STANDARD);
            assertThat(row.getConfigId()).isEqualTo(CONFIG_ID);
            assertThat(row.getCategoryId()).isEqualTo(CATEGORY_ID);
            assertThat(row.isHasCostState()).isFalse();
        });
    }

    @Test
    @DisplayName("a SKU with an active SKU-scoped config is shielded and excluded")
    void impact_excludesSkusShieldedByAnActiveSkuScopeConfig() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.STANDARD));
        givenReplicas(replica(PRODUCT_A, CATEGORY), replica(PRODUCT_B, CATEGORY));
        when(configRepository.findByScopeTypeAndScopeValueInAndActiveTrue(
                        org.mockito.ArgumentMatchers.eq(CostingScopeType.SKU), anyCollection()))
                .thenReturn(List.of(CostingMethodConfig.builder()
                        .configId(UUID.randomUUID())
                        .scopeType(CostingScopeType.SKU)
                        .scopeValue(PRODUCT_A.toString())
                        .method(CostingMethod.STANDARD)
                        .active(true)
                        .build()));

        SkuCategoryImpactResponse report = service(false).impact();

        // Both SKUs were evaluated; only the unshielded one can actually move.
        assertThat(report.getEvaluatedSkuCount()).isEqualTo(2);
        assertThat(report.getImpactedSkus())
                .extracting(SkuCategoryImpactRow::getStockItemId)
                .containsExactly(PRODUCT_B.toString());
    }

    @Test
    @DisplayName("a SKU whose projected method already equals its current method is a no-op and is excluded")
    void impact_excludesSkusWhoseProjectedMethodEqualsTheCurrentMethod() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.AVERAGE));
        givenReplicas(replica(PRODUCT_A, CATEGORY));

        SkuCategoryImpactResponse report = service(false).impact();

        assertThat(report.getEvaluatedSkuCount()).isEqualTo(1);
        assertThat(report.getImpactedSkuCount()).isZero();
        assertThat(report.getImpactedSkus()).isEmpty();
    }

    @Test
    @DisplayName("the current method comes from the active DEFAULT config row when there is one")
    void impact_currentMethodComesFromTheActiveDefaultConfigWhenPresent() {
        when(configRepository.findByScopeTypeAndScopeValueIsNullAndActiveTrue(CostingScopeType.DEFAULT))
                .thenReturn(Optional.of(CostingMethodConfig.builder()
                        .configId(UUID.randomUUID())
                        .scopeType(CostingScopeType.DEFAULT)
                        .method(CostingMethod.STANDARD)
                        .active(true)
                        .build()));
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.AVERAGE));
        givenReplicas(replica(PRODUCT_A, CATEGORY));

        SkuCategoryImpactResponse report = service(false).impact();

        assertThat(report.getActiveDefaultConfigMethod()).isEqualTo(CostingMethod.STANDARD);
        assertThat(report.getDeploymentDefaultMethod()).isEqualTo(CostingMethod.AVERAGE);
        // Current is STANDARD (the DEFAULT row), projected AVERAGE -> a real change.
        assertThat(report.getImpactedSkus()).singleElement().satisfies(row -> {
            assertThat(row.getCurrentMethod()).isEqualTo(CostingMethod.STANDARD);
            assertThat(row.getProjectedMethod()).isEqualTo(CostingMethod.AVERAGE);
        });
    }

    @Test
    @DisplayName("with no DEFAULT config the current method falls back to the deployment default")
    void impact_currentMethodFallsBackToTheDeploymentDefaultWithNoDefaultConfig() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.STANDARD));
        givenReplicas(replica(PRODUCT_A, CATEGORY));

        SkuCategoryImpactResponse report = service(false).impact();

        assertThat(report.getActiveDefaultConfigMethod()).isNull();
        assertThat(report.getImpactedSkus())
                .singleElement()
                .satisfies(row -> assertThat(row.getCurrentMethod()).isEqualTo(CostingMethod.AVERAGE));
    }

    @Test
    @DisplayName("configured categories that match no replicated product are called out")
    void impact_reportsConfiguredCategoriesWithNoReplicatedProducts() {
        givenCategoryConfig(
                categoryConfig(CATEGORY, CostingMethod.STANDARD),
                CostingMethodConfig.builder()
                        .configId(UUID.randomUUID())
                        .scopeType(CostingScopeType.SKU_CATEGORY)
                        .scopeValue("Zzz Nonexistent")
                        .method(CostingMethod.STANDARD)
                        .active(true)
                        .build());
        givenReplicas(replica(PRODUCT_A, CATEGORY));

        SkuCategoryImpactResponse report = service(false).impact();

        assertThat(report.getCategoriesWithNoReplicatedProducts()).containsExactly("Zzz Nonexistent");
    }

    @Test
    @DisplayName("cost state is attached and flagged when the SKU has one")
    void impact_attachesCostStateWhenPresentAndFlagsHasCostState() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.STANDARD));
        givenReplicas(replica(PRODUCT_A, CATEGORY), replica(PRODUCT_B, CATEGORY));
        when(skuCostStateRepository.findByStockItemIdIn(anyCollection()))
                .thenReturn(List.of(SkuCostState.builder()
                        .costStateId(UUID.randomUUID())
                        .stockItemId(PRODUCT_A.toString())
                        .onHandQty(new BigDecimal("42.0000"))
                        .avgCost(new BigDecimal("12.500000"))
                        .standardCost(new BigDecimal("13.000000"))
                        .build()));

        SkuCategoryImpactResponse report = service(false).impact();

        assertThat(report.getImpactedSkuWithCostStateCount()).isEqualTo(1);
        assertThat(report.getImpactedSkus())
                .filteredOn(row -> row.getStockItemId().equals(PRODUCT_A.toString()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.isHasCostState()).isTrue();
                    assertThat(row.getOnHandQty()).isEqualByComparingTo("42.0000");
                    assertThat(row.getAvgCost()).isEqualByComparingTo("12.500000");
                    assertThat(row.getStandardCost()).isEqualByComparingTo("13.000000");
                });
        assertThat(report.getImpactedSkus())
                .filteredOn(row -> row.getStockItemId().equals(PRODUCT_B.toString()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.isHasCostState()).isFalse();
                    assertThat(row.getOnHandQty()).isNull();
                    assertThat(row.getAvgCost()).isNull();
                    assertThat(row.getStandardCost()).isNull();
                });
    }

    @Test
    @DisplayName("sourcing rows that would start resolving are reported, without claiming today's strategy")
    void impact_reportsSourcingStrategyRowsThatWouldStartResolving() {
        when(sourcingStrategyConfigRepository.findByScopeTypeAndActiveTrue(SourcingScopeType.SKU_CATEGORY))
                .thenReturn(List.of(SourcingStrategyConfig.builder()
                        .configId(SOURCING_CONFIG_ID)
                        .scopeType(SourcingScopeType.SKU_CATEGORY)
                        .scopeValue(CATEGORY)
                        .strategy(SourcingStrategy.FEFO)
                        .active(true)
                        .build()));
        givenReplicas(replica(PRODUCT_A, CATEGORY));

        SkuCategoryImpactResponse report = service(false).impact();

        assertThat(report.getImpactedSourcingSkus()).singleElement().satisfies(row -> {
            assertThat(row.getStockItemId()).isEqualTo(PRODUCT_A.toString());
            assertThat(row.getCategoryName()).isEqualTo(CATEGORY);
            assertThat(row.getConfigId()).isEqualTo(SOURCING_CONFIG_ID);
            assertThat(row.getProjectedStrategy()).isEqualTo(SourcingStrategy.FEFO);
        });
    }

    @Test
    @DisplayName("category matching is exact after trimming, so a casing mismatch is reported as unmatched")
    void impact_matchesCategoryNameExactlyAfterTrimming() {
        givenCategoryConfig(categoryConfig("  " + CATEGORY + "  ", CostingMethod.STANDARD));
        // Same name modulo surrounding whitespace matches; different casing does not.
        givenReplicas(replica(PRODUCT_A, " " + CATEGORY + " "), replica(PRODUCT_B, "electrical system"));

        SkuCategoryImpactResponse report = service(false).impact();

        assertThat(report.getImpactedSkus())
                .extracting(SkuCategoryImpactRow::getStockItemId)
                .containsExactly(PRODUCT_A.toString());
        assertThat(report.getCategoriesWithNoReplicatedProducts()).isEmpty();
    }

    @Test
    @DisplayName("the whole point: the report is non-empty while resolve-from-replica is still false")
    void impact_worksWhileResolveFromReplicaIsFalse() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.STANDARD));
        givenReplicas(replica(PRODUCT_A, CATEGORY));

        SkuCategoryImpactResponse report = service(false).impact();

        // If this class ever grows a SkuCategoryProvider dependency, the flag would gag the SPI and
        // this report would be empty at exactly the moment an operator needs it.
        assertThat(report.isResolveFromReplicaEnabled()).isFalse();
        assertThat(report.getImpactedSkuCount()).isEqualTo(1);
        assertThat(report.getImpactedSkus()).isNotEmpty();
    }

    /** Guards the constructor contract the class javadoc rests on. */
    @Test
    @DisplayName("the impl declares no SkuCategoryProvider collaborator")
    void impl_hasNoSkuCategoryProviderDependency() {
        List<Class<?>> fieldTypes = java.util.Arrays.stream(SkuCategoryCutoverServiceImpl.class.getDeclaredFields())
                .<Class<?>>map(java.lang.reflect.Field::getType)
                .toList();

        assertThat(fieldTypes).doesNotContain(SkuCategoryProvider.class);
    }
}
