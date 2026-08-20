package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Per-strategy ordering tests (odoo-parity H1, issue #1037), including the
 * documented deterministic tie-breakers (ascending location id).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Sourcing ordering strategies")
class SourcingOrderingStrategiesTest {

    private static final String SKU = "01960003-0000-7000-8000-0000000000aa";
    // Location ids chosen so ascending-id tie-breaks are observable.
    private static final UUID LOC_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID LOC_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID LOC_C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Mock
    private InventoryLedgerEntryRepository ledgerRepository;

    @Mock
    private InventoryStockSummaryRepository stockSummaryRepository;

    @Mock
    private ExtStorageLocationReplicaRepository storageLocationRepository;

    @Mock
    private ExtLocationParentReplicaRepository locationParentRepository;

    private static SourcingSelection selectionOf(UUID referenceLocationId, SourcingCandidate... candidates) {
        return new SourcingSelection(SKU, null, referenceLocationId, List.of(candidates));
    }

    private static List<UUID> locations(List<SourcingCandidate> ordered) {
        return ordered.stream().map(SourcingCandidate::locationId).toList();
    }

    private static InventoryLedgerEntryRepository.LocationEarliestReceipt receipt(UUID locationId, Instant at) {
        return new InventoryLedgerEntryRepository.LocationEarliestReceipt() {
            @Override
            public UUID getLocationId() {
                return locationId;
            }

            @Override
            public Instant getEarliestReceipt() {
                return at;
            }
        };
    }

    // ─── FIFO ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FIFO orders by earliest receipt, no-history locations last, tie-break by location id")
    void fifo_ordersByEarliestReceipt() {
        FifoSourcingStrategy fifo = new FifoSourcingStrategy(ledgerRepository);
        when(ledgerRepository.findEarliestReceiptByLocation(eq(SKU), anyCollection(), anyCollection()))
                .thenReturn(List.of(
                        receipt(LOC_B, Instant.parse("2026-01-01T00:00:00Z")),
                        receipt(LOC_C, Instant.parse("2026-02-01T00:00:00Z"))));

        List<SourcingCandidate> ordered = fifo.order(selectionOf(
                null,
                new SourcingCandidate(LOC_A, null), // no receipt history → last
                new SourcingCandidate(LOC_C, null),
                new SourcingCandidate(LOC_B, null)));

        assertThat(locations(ordered)).containsExactly(LOC_B, LOC_C, LOC_A);
    }

    @Test
    @DisplayName("FIFO tie on equal receipt timestamps breaks by ascending location id")
    void fifo_tieBreaksByLocationId() {
        FifoSourcingStrategy fifo = new FifoSourcingStrategy(ledgerRepository);
        Instant same = Instant.parse("2026-01-01T00:00:00Z");
        when(ledgerRepository.findEarliestReceiptByLocation(eq(SKU), anyCollection(), anyCollection()))
                .thenReturn(List.of(receipt(LOC_B, same), receipt(LOC_A, same)));

        List<SourcingCandidate> ordered =
                fifo.order(selectionOf(null, new SourcingCandidate(LOC_B, null), new SourcingCandidate(LOC_A, null)));

        assertThat(locations(ordered)).containsExactly(LOC_A, LOC_B);
    }

    // ─── FEFO ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FEFO orders by earliest lot expiry from the LotExpiryProvider, no-lot locations last")
    void fefo_ordersByEarliestExpiry() {
        LotExpiryProvider provider = new LotExpiryProvider() {
            @Override
            public boolean hasExpiryData(String stockItemId) {
                return true;
            }

            @Override
            public Map<UUID, Instant> earliestExpiryByLocation(String stockItemId, Collection<UUID> locationIds) {
                return Map.of(
                        LOC_C, Instant.parse("2026-03-01T00:00:00Z"),
                        LOC_B, Instant.parse("2026-06-01T00:00:00Z"));
            }
        };
        FefoSourcingStrategy fefo = new FefoSourcingStrategy(provider);

        List<SourcingCandidate> ordered = fefo.order(selectionOf(
                null,
                new SourcingCandidate(LOC_A, null), // no expiring lots → last
                new SourcingCandidate(LOC_B, null),
                new SourcingCandidate(LOC_C, null)));

        assertThat(locations(ordered)).containsExactly(LOC_C, LOC_B, LOC_A);
    }

    // ─── HIGHEST_STOCK ───────────────────────────────────────────────────────

    @Test
    @DisplayName("HIGHEST_STOCK orders most-available first, summary lookup for unknown quantities, id tie-break")
    void highestStock_ordersByAvailableDescending() {
        HighestStockSourcingStrategy highestStock = new HighestStockSourcingStrategy(stockSummaryRepository);
        // LOC_A quantity is unknown to the caller → read from the stock summary.
        when(stockSummaryRepository.findByStockItemIdAndLocationId(SKU, LOC_A))
                .thenReturn(Optional.of(InventoryStockSummary.builder()
                        .stockItemId(SKU)
                        .locationId(LOC_A)
                        .onHand(new BigDecimal("9"))
                        .allocated(new BigDecimal("2"))
                        .build()));

        List<SourcingCandidate> ordered = highestStock.order(selectionOf(
                null,
                new SourcingCandidate(LOC_A, null), // 9 - 2 = 7 available
                new SourcingCandidate(LOC_B, new BigDecimal("3")),
                new SourcingCandidate(LOC_C, new BigDecimal("7")))); // ties with LOC_A → id tie-break

        assertThat(locations(ordered)).containsExactly(LOC_A, LOC_C, LOC_B);
    }

    // ─── PROXIMITY ───────────────────────────────────────────────────────────

    /**
     * Topology: site S → zones Z1, Z2; Z1 → bins REF and LOC_A; Z2 → LOC_B.
     * LOC_C is not connected. From REF: LOC_A is 2 hops (REF → Z1 → LOC_A),
     * LOC_B is 4 hops (REF → Z1 → S → Z2 → LOC_B), LOC_C unreachable → last.
     */
    @Test
    @DisplayName("PROXIMITY orders same-zone first by BFS hop distance; unreachable candidates last")
    void proximity_ordersByHopDistance() {
        UUID site = UUID.fromString("00000000-0000-0000-0000-000000000051");
        UUID zone1 = UUID.fromString("00000000-0000-0000-0000-000000000052");
        UUID zone2 = UUID.fromString("00000000-0000-0000-0000-000000000053");
        UUID ref = UUID.fromString("00000000-0000-0000-0000-000000000054");

        Map<UUID, UUID> parentOf = Map.of(ref, zone1, LOC_A, zone1, LOC_B, zone2, zone1, site, zone2, site);
        stubStorageTopology(parentOf, site);

        ProximitySourcingStrategy proximity =
                new ProximitySourcingStrategy(storageLocationRepository, locationParentRepository);

        List<SourcingCandidate> ordered = proximity.order(selectionOf(
                ref,
                new SourcingCandidate(LOC_B, null),
                new SourcingCandidate(LOC_C, null),
                new SourcingCandidate(LOC_A, null)));

        assertThat(locations(ordered)).containsExactly(LOC_A, LOC_B, LOC_C);
    }

    private void stubStorageTopology(Map<UUID, UUID> parentOf, UUID siteId) {
        when(storageLocationRepository.findAllById(any())).thenAnswer(invocation -> {
            Iterable<UUID> ids = invocation.getArgument(0);
            java.util.List<ExtStorageLocationReplica> result = new java.util.ArrayList<>();
            for (UUID id : ids) {
                if (parentOf.containsKey(id) || parentOf.containsValue(id)) {
                    result.add(ExtStorageLocationReplica.builder()
                            .storageLocationId(id)
                            .siteId(siteId)
                            .parentStorageLocationId(parentOf.get(id))
                            .build());
                }
            }
            return result;
        });
        when(storageLocationRepository.findByParentStorageLocationIdIn(anyCollection()))
                .thenAnswer(invocation -> {
                    Collection<UUID> parents = invocation.getArgument(0);
                    return parentOf.entrySet().stream()
                            .filter(entry -> parents.contains(entry.getValue()))
                            .map(entry -> ExtStorageLocationReplica.builder()
                                    .storageLocationId(entry.getKey())
                                    .siteId(siteId)
                                    .parentStorageLocationId(entry.getValue())
                                    .build())
                            .toList();
                });
        when(locationParentRepository.findByChildIdIn(anyCollection())).thenReturn(List.of());
        when(locationParentRepository.findByParentIdIn(anyCollection())).thenReturn(List.of());
    }

    // ─── Strategy keys ───────────────────────────────────────────────────────

    @Test
    @DisplayName("each ordering bean reports its strategy key")
    void strategyKeys() {
        assertThat(new FifoSourcingStrategy(ledgerRepository).strategy()).isEqualTo(SourcingStrategy.FIFO);
        assertThat(new FefoSourcingStrategy(new NoOpLotExpiryProvider()).strategy())
                .isEqualTo(SourcingStrategy.FEFO);
        assertThat(new ProximitySourcingStrategy(storageLocationRepository, locationParentRepository).strategy())
                .isEqualTo(SourcingStrategy.PROXIMITY);
        assertThat(new HighestStockSourcingStrategy(stockSummaryRepository).strategy())
                .isEqualTo(SourcingStrategy.HIGHEST_STOCK);
    }
}
