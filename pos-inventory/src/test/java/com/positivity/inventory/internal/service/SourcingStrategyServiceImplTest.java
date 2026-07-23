package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.sourcing.SourcingStrategyConfigRequest;
import com.positivity.inventory.internal.dto.sourcing.SourcingStrategyConfigResponse;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.SourcingStrategyConfig;
import com.positivity.inventory.internal.enums.SourcingScopeType;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.SourcingStrategyConfigRepository;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourceSelection;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingDecision;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Resolution-precedence and fallback tests for the sourcing strategy engine
 * (odoo-parity H1, issue #1037): SKU_CATEGORY → SITE → DEFAULT → platform
 * default FIFO; FEFO→FIFO without lot data; PROXIMITY→FIFO without a
 * reference location; F5-shaped {@code selectSource} surplus picking.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SourcingStrategyServiceImpl")
class SourcingStrategyServiceImplTest {

    private static final String SKU = "01960003-0000-7000-8000-0000000000aa";
    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000051");
    private static final UUID LOC_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID LOC_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID REFERENCE = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Mock
    private SourcingStrategyConfigRepository configRepository;

    @Mock
    private InventoryStockSummaryRepository stockSummaryRepository;

    @Mock
    private ExtStorageLocationReplicaRepository storageLocationRepository;

    @Mock
    private SkuCategoryProvider skuCategoryProvider;

    @Mock
    private LotExpiryProvider lotExpiryProvider;

    private SourcingStrategyServiceImpl service;

    /** Marker orderings so the dispatched strategy is observable from the result. */
    private static SourcingOrderingStrategy marker(SourcingStrategy strategy, List<SourcingCandidate> result) {
        return new SourcingOrderingStrategy() {
            @Override
            public SourcingStrategy strategy() {
                return strategy;
            }

            @Override
            public List<SourcingCandidate> order(SourcingSelection selection) {
                return result;
            }
        };
    }

    private final List<SourcingCandidate> fifoOrder =
            List.of(new SourcingCandidate(LOC_A, null), new SourcingCandidate(LOC_B, null));
    private final List<SourcingCandidate> proximityOrder =
            List.of(new SourcingCandidate(LOC_B, null), new SourcingCandidate(LOC_A, null));
    private final List<SourcingCandidate> fefoOrder =
            List.of(new SourcingCandidate(LOC_B, null), new SourcingCandidate(LOC_A, null));
    private final List<SourcingCandidate> highestStockOrder =
            List.of(new SourcingCandidate(LOC_B, null), new SourcingCandidate(LOC_A, null));

    @BeforeEach
    void setUp() {
        // FIFO passes the candidates through unchanged (identity order) so the
        // selectSource tests keep their caller-provided quantities; the other
        // markers return fixed lists so the dispatched strategy is observable.
        SourcingOrderingStrategy fifoPassThrough = new SourcingOrderingStrategy() {
            @Override
            public SourcingStrategy strategy() {
                return SourcingStrategy.FIFO;
            }

            @Override
            public List<SourcingCandidate> order(SourcingSelection selection) {
                return selection.candidates();
            }
        };
        service = new SourcingStrategyServiceImpl(
                configRepository,
                stockSummaryRepository,
                storageLocationRepository,
                skuCategoryProvider,
                lotExpiryProvider,
                List.of(
                        fifoPassThrough,
                        marker(SourcingStrategy.FEFO, fefoOrder),
                        marker(SourcingStrategy.PROXIMITY, proximityOrder),
                        marker(SourcingStrategy.HIGHEST_STOCK, highestStockOrder)));
        when(skuCategoryProvider.categoryOf(any())).thenReturn(Optional.empty());
        when(lotExpiryProvider.hasExpiryData(any())).thenReturn(false);
        when(configRepository.findByScopeTypeAndScopeValueAndActiveTrue(any(), any()))
                .thenReturn(Optional.empty());
        when(configRepository.findByScopeTypeAndScopeValueIsNullAndActiveTrue(any()))
                .thenReturn(Optional.empty());
        when(storageLocationRepository.findById(any())).thenReturn(Optional.empty());
    }

    private void stubSiteConfig(SourcingStrategy strategy) {
        when(configRepository.findByScopeTypeAndScopeValueAndActiveTrue(SourcingScopeType.SITE, SITE_ID.toString()))
                .thenReturn(Optional.of(SourcingStrategyConfig.builder()
                        .scopeType(SourcingScopeType.SITE)
                        .scopeValue(SITE_ID.toString())
                        .strategy(strategy)
                        .active(true)
                        .build()));
    }

    private SourcingSelection selection(UUID siteId, UUID referenceLocationId) {
        return new SourcingSelection(
                SKU,
                siteId,
                referenceLocationId,
                List.of(new SourcingCandidate(LOC_A, null), new SourcingCandidate(LOC_B, null)));
    }

    // ─── Resolution precedence ───────────────────────────────────────────────

    @Test
    @DisplayName("no config anywhere → platform default FIFO")
    void resolution_platformDefaultIsFifo() {
        SourcingDecision decision = service.orderCandidates(selection(SITE_ID, null));

        assertThat(decision.configuredStrategy()).isEqualTo(SourcingStrategy.FIFO);
        assertThat(decision.effectiveStrategy()).isEqualTo(SourcingStrategy.FIFO);
        assertThat(decision.orderedCandidates()).isEqualTo(fifoOrder);
    }

    @Test
    @DisplayName("DEFAULT-scope config beats the platform default")
    void resolution_defaultScopeConfigApplies() {
        when(configRepository.findByScopeTypeAndScopeValueIsNullAndActiveTrue(SourcingScopeType.DEFAULT))
                .thenReturn(Optional.of(SourcingStrategyConfig.builder()
                        .scopeType(SourcingScopeType.DEFAULT)
                        .strategy(SourcingStrategy.HIGHEST_STOCK)
                        .active(true)
                        .build()));

        SourcingDecision decision = service.orderCandidates(selection(SITE_ID, null));

        assertThat(decision.effectiveStrategy()).isEqualTo(SourcingStrategy.HIGHEST_STOCK);
        assertThat(decision.orderedCandidates()).isEqualTo(highestStockOrder);
    }

    @Test
    @DisplayName("SITE config beats DEFAULT config")
    void resolution_siteBeatsDefault() {
        stubSiteConfig(SourcingStrategy.HIGHEST_STOCK);
        when(configRepository.findByScopeTypeAndScopeValueIsNullAndActiveTrue(SourcingScopeType.DEFAULT))
                .thenReturn(Optional.of(SourcingStrategyConfig.builder()
                        .scopeType(SourcingScopeType.DEFAULT)
                        .strategy(SourcingStrategy.FIFO)
                        .active(true)
                        .build()));

        SourcingDecision decision = service.orderCandidates(selection(SITE_ID, null));

        assertThat(decision.configuredStrategy()).isEqualTo(SourcingStrategy.HIGHEST_STOCK);
    }

    @Test
    @DisplayName("category unresolvable (no catalog category source) → SKU_CATEGORY config skipped, SITE wins")
    void resolution_unresolvableCategorySkipsToSite() {
        // An active SKU_CATEGORY row exists, but ext_product carries no
        // category, so the provider resolves nothing and the row is skipped.
        when(configRepository.findByScopeTypeAndScopeValueAndActiveTrue(SourcingScopeType.SKU_CATEGORY, "BRAKES"))
                .thenReturn(Optional.of(SourcingStrategyConfig.builder()
                        .scopeType(SourcingScopeType.SKU_CATEGORY)
                        .scopeValue("BRAKES")
                        .strategy(SourcingStrategy.FEFO)
                        .active(true)
                        .build()));
        stubSiteConfig(SourcingStrategy.HIGHEST_STOCK);

        SourcingDecision decision = service.orderCandidates(selection(SITE_ID, null));

        assertThat(decision.configuredStrategy()).isEqualTo(SourcingStrategy.HIGHEST_STOCK);
    }

    @Test
    @DisplayName("resolvable category → SKU_CATEGORY config beats SITE config")
    void resolution_categoryBeatsSite() {
        when(skuCategoryProvider.categoryOf(SKU)).thenReturn(Optional.of("BRAKES"));
        when(configRepository.findByScopeTypeAndScopeValueAndActiveTrue(SourcingScopeType.SKU_CATEGORY, "BRAKES"))
                .thenReturn(Optional.of(SourcingStrategyConfig.builder()
                        .scopeType(SourcingScopeType.SKU_CATEGORY)
                        .scopeValue("BRAKES")
                        .strategy(SourcingStrategy.HIGHEST_STOCK)
                        .active(true)
                        .build()));
        stubSiteConfig(SourcingStrategy.FIFO);

        SourcingDecision decision = service.orderCandidates(selection(SITE_ID, null));

        assertThat(decision.configuredStrategy()).isEqualTo(SourcingStrategy.HIGHEST_STOCK);
    }

    @Test
    @DisplayName("no explicit site → derived from the first candidate's storage-location replica")
    void resolution_siteDerivedFromCandidates() {
        when(storageLocationRepository.findById(LOC_A))
                .thenReturn(Optional.of(ExtStorageLocationReplica.builder()
                        .storageLocationId(LOC_A)
                        .siteId(SITE_ID)
                        .build()));
        stubSiteConfig(SourcingStrategy.HIGHEST_STOCK);

        SourcingDecision decision = service.orderCandidates(selection(null, null));

        assertThat(decision.configuredStrategy()).isEqualTo(SourcingStrategy.HIGHEST_STOCK);
    }

    // ─── Fallbacks ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("FEFO without lot-expiry data falls back to FIFO ordering (lots arrive with E1-E3)")
    void fefo_withoutLotData_fallsBackToFifo() {
        stubSiteConfig(SourcingStrategy.FEFO);

        SourcingDecision decision = service.orderCandidates(selection(SITE_ID, null));

        assertThat(decision.configuredStrategy()).isEqualTo(SourcingStrategy.FEFO);
        assertThat(decision.effectiveStrategy()).isEqualTo(SourcingStrategy.FIFO);
        assertThat(decision.orderedCandidates()).isEqualTo(fifoOrder);
    }

    @Test
    @DisplayName("FEFO with lot-expiry data dispatches to the registered FEFO ordering")
    void fefo_withLotData_usesFefoOrdering() {
        stubSiteConfig(SourcingStrategy.FEFO);
        when(lotExpiryProvider.hasExpiryData(SKU)).thenReturn(true);

        SourcingDecision decision = service.orderCandidates(selection(SITE_ID, null));

        assertThat(decision.effectiveStrategy()).isEqualTo(SourcingStrategy.FEFO);
        assertThat(decision.orderedCandidates()).isEqualTo(fefoOrder);
    }

    @Test
    @DisplayName("PROXIMITY without a reference location falls back to FIFO ordering")
    void proximity_withoutReference_fallsBackToFifo() {
        stubSiteConfig(SourcingStrategy.PROXIMITY);

        SourcingDecision decision = service.orderCandidates(selection(SITE_ID, null));

        assertThat(decision.configuredStrategy()).isEqualTo(SourcingStrategy.PROXIMITY);
        assertThat(decision.effectiveStrategy()).isEqualTo(SourcingStrategy.FIFO);
    }

    @Test
    @DisplayName("PROXIMITY with a reference location dispatches to the proximity ordering")
    void proximity_withReference_usesProximityOrdering() {
        stubSiteConfig(SourcingStrategy.PROXIMITY);

        SourcingDecision decision = service.orderCandidates(selection(SITE_ID, REFERENCE));

        assertThat(decision.effectiveStrategy()).isEqualTo(SourcingStrategy.PROXIMITY);
        assertThat(decision.orderedCandidates()).isEqualTo(proximityOrder);
    }

    // ─── selectSource (F5 shape) ─────────────────────────────────────────────

    @Test
    @DisplayName("selectSource returns the first strategy-ordered candidate with enough available stock")
    void selectSource_picksFirstCandidateWithSurplus() {
        // Default FIFO order is [LOC_A, LOC_B]; LOC_A has too little.
        SourcingSelection selection = new SourcingSelection(
                SKU, SITE_ID, null, List.of(new SourcingCandidate(LOC_A, 3L), new SourcingCandidate(LOC_B, 10L)));

        Optional<SourceSelection> picked = service.selectSource(selection, 5);

        assertThat(picked).isPresent();
        assertThat(picked.orElseThrow().candidate().locationId()).isEqualTo(LOC_B);
        assertThat(picked.orElseThrow().decision().effectiveStrategy()).isEqualTo(SourcingStrategy.FIFO);
    }

    @Test
    @DisplayName("selectSource is empty when no candidate covers the needed quantity")
    void selectSource_emptyWithoutSurplus() {
        SourcingSelection selection = new SourcingSelection(
                SKU, SITE_ID, null, List.of(new SourcingCandidate(LOC_A, 3L), new SourcingCandidate(LOC_B, 4L)));

        assertThat(service.selectSource(selection, 5)).isEmpty();
    }

    // ─── Admin operations ────────────────────────────────────────────────────

    @Test
    @DisplayName("upsert creates a new active row for a new scope")
    void upsert_createsRow() {
        when(configRepository.findByScopeTypeAndScopeValue(SourcingScopeType.SITE, SITE_ID.toString()))
                .thenReturn(Optional.empty());
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SourcingStrategyConfigResponse response = service.upsertConfig(SourcingStrategyConfigRequest.builder()
                .scopeType(SourcingScopeType.SITE)
                .scopeValue(SITE_ID.toString())
                .strategy(SourcingStrategy.PROXIMITY)
                .build());

        assertThat(response.getScopeType()).isEqualTo(SourcingScopeType.SITE);
        assertThat(response.getScopeValue()).isEqualTo(SITE_ID.toString());
        assertThat(response.getStrategy()).isEqualTo(SourcingStrategy.PROXIMITY);
        assertThat(response.isActive()).isTrue();
    }

    @Test
    @DisplayName("upsert reuses and reactivates the existing row for the scope")
    void upsert_reusesExistingRow() {
        UUID existingId = UUID.fromString("00000000-0000-0000-0000-000000000077");
        when(configRepository.findByScopeTypeAndScopeValueIsNull(SourcingScopeType.DEFAULT))
                .thenReturn(Optional.of(SourcingStrategyConfig.builder()
                        .configId(existingId)
                        .scopeType(SourcingScopeType.DEFAULT)
                        .strategy(SourcingStrategy.FIFO)
                        .active(false)
                        .build()));
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SourcingStrategyConfigResponse response = service.upsertConfig(SourcingStrategyConfigRequest.builder()
                .scopeType(SourcingScopeType.DEFAULT)
                .strategy(SourcingStrategy.HIGHEST_STOCK)
                .build());

        assertThat(response.getConfigId()).isEqualTo(existingId);
        assertThat(response.getStrategy()).isEqualTo(SourcingStrategy.HIGHEST_STOCK);
        assertThat(response.isActive()).isTrue();
    }

    @Test
    @DisplayName("upsert validates scopeValue per scope type")
    void upsert_validatesScopeValue() {
        assertThatThrownBy(() -> service.upsertConfig(SourcingStrategyConfigRequest.builder()
                        .scopeType(SourcingScopeType.DEFAULT)
                        .scopeValue("nope")
                        .strategy(SourcingStrategy.FIFO)
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DEFAULT");
        assertThatThrownBy(() -> service.upsertConfig(SourcingStrategyConfigRequest.builder()
                        .scopeType(SourcingScopeType.SITE)
                        .scopeValue("not-a-uuid")
                        .strategy(SourcingStrategy.FIFO)
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("site UUID");
        assertThatThrownBy(() -> service.upsertConfig(SourcingStrategyConfigRequest.builder()
                        .scopeType(SourcingScopeType.SKU_CATEGORY)
                        .strategy(SourcingStrategy.FIFO)
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category");
    }

    @Test
    @DisplayName("deactivate flips active to false; unknown id → ResourceNotFoundException")
    void deactivate_softDeletes() {
        UUID configId = UUID.fromString("00000000-0000-0000-0000-000000000078");
        when(configRepository.findById(configId))
                .thenReturn(Optional.of(SourcingStrategyConfig.builder()
                        .configId(configId)
                        .scopeType(SourcingScopeType.DEFAULT)
                        .strategy(SourcingStrategy.FIFO)
                        .active(true)
                        .build()));
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.deactivateConfig(configId).isActive()).isFalse();

        UUID missing = UUID.fromString("00000000-0000-0000-0000-000000000079");
        when(configRepository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivateConfig(missing)).isInstanceOf(ResourceNotFoundException.class);
    }
}
