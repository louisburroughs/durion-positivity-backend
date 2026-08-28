package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    private static final int DEFAULT_CAP = 5000;

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
        when(extProductReplicaRepository.findByTrimmedCategoryNameIn(anyCollection(), any()))
                .thenReturn(List.of());
        when(extProductReplicaRepository.countByTrimmedCategoryNameIn(anyCollection()))
                .thenReturn(0L);
        when(extProductReplicaRepository.findDistinctTrimmedCategoryNamesIn(anyCollection()))
                .thenReturn(List.of());
        when(skuCostStateRepository.findByStockItemIdIn(anyCollection())).thenReturn(List.of());
    }

    private SkuCategoryCutoverServiceImpl service(boolean resolveFromReplicaEnabled) {
        return service(resolveFromReplicaEnabled, DEFAULT_CAP);
    }

    private SkuCategoryCutoverServiceImpl service(boolean resolveFromReplicaEnabled, int impactSkuCap) {
        return new SkuCategoryCutoverServiceImpl(
                configRepository,
                sourcingStrategyConfigRepository,
                extProductReplicaRepository,
                skuCostStateRepository,
                costingMethodResolver,
                resolveFromReplicaEnabled,
                impactSkuCap);
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

    /**
     * Stubs the scan, the population count and the distinct-name probe consistently, so a test never
     * asserts against a replica set the count query disagrees with.
     */
    private void givenReplicas(ExtProductReplica... replicas) {
        List<ExtProductReplica> rows = List.of(replicas);
        when(extProductReplicaRepository.findByTrimmedCategoryNameIn(anyCollection(), any()))
                .thenReturn(rows);
        when(extProductReplicaRepository.countByTrimmedCategoryNameIn(anyCollection()))
                .thenReturn((long) rows.size());
        when(extProductReplicaRepository.findDistinctTrimmedCategoryNamesIn(anyCollection()))
                .thenAnswer(invocation -> rows.stream()
                        .map(replica -> replica.getCategoryName() == null
                                ? null
                                : replica.getCategoryName().trim())
                        .filter(name -> name != null && !name.isEmpty())
                        .distinct()
                        .toList());
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
    @DisplayName("matching is verbatim on the stored scope value, as the resolver matches")
    void impact_matchesCategoryNameExactlyAsTheRuntimeDoes() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.STANDARD));
        // The replica side is trimmed by the SPI, so surrounding whitespace there is harmless;
        // different casing is not, because the resolver's map lookup is case-sensitive.
        givenReplicas(replica(PRODUCT_A, " " + CATEGORY + " "), replica(PRODUCT_B, "electrical system"));

        SkuCategoryImpactResponse report = service(false).impact();

        assertThat(report.getImpactedSkus())
                .extracting(SkuCategoryImpactRow::getStockItemId)
                .containsExactly(PRODUCT_A.toString());
    }

    @Test
    @DisplayName("a config scope value with whitespace can never fire, and is reported as such rather than trimmed")
    void impact_reportsUntrimmedScopeValuesAsUnmatchableRatherThanTrimmingThem() {
        givenCategoryConfig(categoryConfig("  " + CATEGORY + "  ", CostingMethod.STANDARD));
        givenReplicas(replica(PRODUCT_A, CATEGORY));

        SkuCategoryImpactResponse report = service(false).impact();

        // CostingMethodResolver keys on the STORED scope value verbatim while the category arrives
        // trimmed, so this row never fires at runtime. The report must say so, not quietly trim it
        // into working — the API trims on write, so a row like this is seeded or hand-inserted, and
        // being right about hand-authored config is this report's whole job.
        assertThat(report.getCategoriesWithUntrimmedScopeValue()).containsExactly("  " + CATEGORY + "  ");
        assertThat(report.getImpactedSkus()).isEmpty();
        assertThat(report.getCategoryMatchedSkuCount()).isZero();
    }

    @Test
    @DisplayName("the report is non-empty while resolve-from-replica is false, and never consults the gated SPI")
    void impact_worksWhileResolveFromReplicaIsFalseWithoutTouchingTheSpi() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.STANDARD));
        givenReplicas(replica(PRODUCT_A, CATEGORY));

        SkuCategoryImpactResponse report = service(false).impact();

        assertThat(report.isResolveFromReplicaEnabled()).isFalse();
        assertThat(report.getImpactedSkuCount()).isEqualTo(1);

        // The load-bearing assertion. CostingMethodResolver.resolve/resolveAll route through the
        // SkuCategoryProvider SPI, which the flag gags — using either would make this report empty at
        // exactly the moment an operator needs it. defaultMethod() is the only safe member, so pin
        // that it is the ONLY one called. A declared-field check cannot catch this; a call to
        // resolver.resolve(sku) would slip straight past it.
        verify(costingMethodResolver, only()).defaultMethod();
    }

    // ─── the flag is an input, not a caption (F1) ────────────────────────────

    @Test
    @DisplayName("with the flag ON a matched SKU already resolves from its category, so nothing is pending")
    void impact_withFlagOn_reportsNoPendingChangeBecauseTheCategoryAlreadyResolves() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.STANDARD));
        givenReplicas(replica(PRODUCT_A, CATEGORY));

        SkuCategoryImpactResponse report = service(true).impact();

        // Same configuration and same replica as the flag-off case above, which reports 1 impacted.
        // This is what lets the report verify its own cut-over: re-running after the flip must be
        // able to reach zero, otherwise runbook step 8 asserts something that can never happen.
        assertThat(report.isResolveFromReplicaEnabled()).isTrue();
        assertThat(report.getImpactedSkuCount()).isZero();
        assertThat(report.getImpactedSkus()).isEmpty();
        // But the SKU is still governed by the category step, and that count does not vanish.
        assertThat(report.getCategoryMatchedSkuCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("categoryMatchedSkuCount counts governed SKUs under either flag state")
    void impact_categoryMatchedSkuCountIsFlagIndependent() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.STANDARD));
        givenReplicas(replica(PRODUCT_A, CATEGORY), replica(PRODUCT_B, CATEGORY));

        assertThat(service(false).impact().getCategoryMatchedSkuCount()).isEqualTo(2);
        assertThat(service(true).impact().getCategoryMatchedSkuCount()).isEqualTo(2);
    }

    // ─── bounds (F2) ────────────────────────────────────────────────────────

    @Test
    @DisplayName("hitting the cap sets truncated and keeps evaluatedSkuCount truthful")
    void impact_atTheCap_setsTruncatedAndStillReportsTheTruePopulation() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.STANDARD));
        // Cap of 1 with two matching products: the scan fetches cap+1 to detect the overflow.
        givenReplicas(replica(PRODUCT_A, CATEGORY), replica(PRODUCT_B, CATEGORY));

        SkuCategoryImpactResponse report = service(false, 1).impact();

        assertThat(report.isTruncated()).isTrue();
        assertThat(report.getImpactSkuCap()).isEqualTo(1);
        assertThat(report.getImpactedSkus()).hasSize(1);
        // The count query is not capped, so the population size stays honest even though the rows
        // are a lower bound. Silently shortening a report used to decide a financial cut-over would
        // be worse than the unboundedness it replaces.
        assertThat(report.getEvaluatedSkuCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a scan inside the cap is not marked truncated")
    void impact_insideTheCap_isNotTruncated() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.STANDARD));
        givenReplicas(replica(PRODUCT_A, CATEGORY));

        assertThat(service(false, 5000).impact().isTruncated()).isFalse();
    }

    @Test
    @DisplayName("the replica scan runs once for costing and sourcing together, not once each")
    void impact_scansTheReplicaOnlyOnceForBothFeeds() {
        givenCategoryConfig(categoryConfig(CATEGORY, CostingMethod.STANDARD));
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

        assertThat(report.getImpactedSkus()).hasSize(1);
        assertThat(report.getImpactedSourcingSkus()).hasSize(1);
        // findByTrimmedCategoryNameIn is a documented sequential scan; running it per feed doubled
        // the most expensive thing this method does.
        verify(extProductReplicaRepository, times(1)).findByTrimmedCategoryNameIn(anyCollection(), any());
    }

    /**
     * A cheap structural backstop for the "no SPI dependency" rule. It is deliberately NOT the main
     * guard — it only inspects declared field types, so a call to {@code costingMethodResolver
     * .resolve(sku)} would route through the gated SPI and still pass here. The behavioural guard is
     * the {@code verify(costingMethodResolver, only()).defaultMethod()} above.
     */
    @Test
    @DisplayName("the impl declares no SkuCategoryProvider collaborator")
    void impl_hasNoSkuCategoryProviderDependency() {
        List<Class<?>> fieldTypes = java.util.Arrays.stream(SkuCategoryCutoverServiceImpl.class.getDeclaredFields())
                .<Class<?>>map(java.lang.reflect.Field::getType)
                .toList();

        assertThat(fieldTypes).isNotEmpty().doesNotContain(SkuCategoryProvider.class);
    }
}
