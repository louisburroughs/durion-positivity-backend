package com.positivity.inventory.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository tests for the bulk grouped on-hand and allocation queries (CAP-218 story #657).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryLedgerEntryRepositoryTest {

    private static final String SKU_A = "SKU-A";
    private static final String SKU_B = "SKU-B";

    @Autowired
    private InventoryLedgerEntryRepository repository;

    private void seed(String stockItemId, UUID locationId, InventoryLedgerEventType eventType, int changeInQuantity) {
        Instant now = Instant.now();
        repository.save(InventoryLedgerEntry.builder()
                .stockItemId(stockItemId)
                .locationId(locationId)
                .eventType(eventType)
                .changeInQuantity(changeInQuantity)
                .quantityAfter(0)
                .transactionUserId("test-user")
                .timestamp(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Test
    @DisplayName("grouped sums are correct per location for mixed event types")
    void sumQuantityByLocationChunkedGroupsAndFiltersByEventType() {
        UUID locationA = UUID.randomUUID();
        UUID locationB = UUID.randomUUID();
        UUID locationC = UUID.randomUUID();

        seed(SKU_A, locationA, InventoryLedgerEventType.GOODS_RECEIPT, 10);
        seed(SKU_B, locationA, InventoryLedgerEventType.GOODS_ISSUE, -4);
        seed(SKU_A, locationA, InventoryLedgerEventType.ALLOCATION_CREATED, 3); // not on-hand affecting
        seed(SKU_A, locationB, InventoryLedgerEventType.TRANSFER_IN, 7);
        seed(SKU_B, locationB, InventoryLedgerEventType.ADJUSTMENT_OUT, -2);

        Set<InventoryLedgerEventType> onHandTypes = InventoryLedgerEventType.onHandAffectingTypes();
        Map<UUID, Long> result =
                repository.sumQuantityByLocationChunked(List.of(locationA, locationB, locationC), onHandTypes);

        assertThat(result).containsOnly(Map.entry(locationA, 6L), Map.entry(locationB, 5L));
        assertThat(result).doesNotContainKey(locationC);

        // grouped result matches the existing single-location aggregate for the same data
        assertThat(result.get(locationA).intValue())
                .isEqualTo(repository.calculateOnHandQuantityAtLocation(locationA, onHandTypes));
        assertThat(result.get(locationB).intValue())
                .isEqualTo(repository.calculateOnHandQuantityAtLocation(locationB, onHandTypes));
    }

    @Test
    @DisplayName("SKU-filtered grouped sums only include the requested stock item")
    void sumQuantityByLocationForSkuChunkedFiltersByStockItem() {
        UUID locationA = UUID.randomUUID();
        UUID locationB = UUID.randomUUID();

        seed(SKU_A, locationA, InventoryLedgerEventType.GOODS_RECEIPT, 10);
        seed(SKU_A, locationA, InventoryLedgerEventType.GOODS_ISSUE, -3);
        seed(SKU_B, locationA, InventoryLedgerEventType.GOODS_RECEIPT, 50);
        seed(SKU_A, locationB, InventoryLedgerEventType.GOODS_RECEIPT, 2);

        Map<UUID, Long> result = repository.sumQuantityByLocationForSkuChunked(
                SKU_A, List.of(locationA, locationB), InventoryLedgerEventType.onHandAffectingTypes());

        assertThat(result).containsOnly(Map.entry(locationA, 7L), Map.entry(locationB, 2L));
    }

    @Test
    @DisplayName("empty locationIds returns empty result without exception")
    void emptyLocationIdsShortCircuit() {
        seed(SKU_A, UUID.randomUUID(), InventoryLedgerEventType.GOODS_RECEIPT, 10);

        assertThatCode(() -> {
                    assertThat(repository.sumQuantityByLocationChunked(
                                    List.of(), InventoryLedgerEventType.onHandAffectingTypes()))
                            .isEmpty();
                    assertThat(repository.sumQuantityByLocationForSkuChunked(
                                    SKU_A, List.of(), InventoryLedgerEventType.onHandAffectingTypes()))
                            .isEmpty();
                    assertThat(repository.calculateOutstandingAllocationsByLocation(List.of()))
                            .isEmpty();
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("chunking handles more than 1000 location ids and merges results")
    void chunkingHandlesMoreThanOneThousandLocationIds() {
        UUID firstLocation = UUID.randomUUID();
        UUID lastLocation = UUID.randomUUID();

        seed(SKU_A, firstLocation, InventoryLedgerEventType.GOODS_RECEIPT, 11);
        seed(SKU_A, lastLocation, InventoryLedgerEventType.GOODS_RECEIPT, 22);
        seed(SKU_A, lastLocation, InventoryLedgerEventType.GOODS_ISSUE, -2);

        List<UUID> locationIds = new ArrayList<>();
        locationIds.add(firstLocation); // lands in the first chunk
        for (int i = 0; i < 999; i++) {
            locationIds.add(UUID.randomUUID());
        }
        locationIds.add(lastLocation); // id #1001, lands in the second chunk
        assertThat(locationIds).hasSize(1001);

        Map<UUID, Long> result =
                repository.sumQuantityByLocationChunked(locationIds, InventoryLedgerEventType.onHandAffectingTypes());

        assertThat(result).containsOnly(Map.entry(firstLocation, 11L), Map.entry(lastLocation, 20L));
    }

    @Test
    @DisplayName("outstanding allocations per location match single-location semantics")
    void outstandingAllocationsMatchSingleLocationSemantics() {
        UUID locationA = UUID.randomUUID();
        UUID locationB = UUID.randomUUID();
        UUID locationC = UUID.randomUUID();

        seed(SKU_A, locationA, InventoryLedgerEventType.ALLOCATION_CREATED, 5);
        seed(SKU_B, locationA, InventoryLedgerEventType.ALLOCATION_CREATED, 3);
        seed(SKU_A, locationA, InventoryLedgerEventType.ALLOCATION_RELEASED, 2);
        seed(SKU_A, locationB, InventoryLedgerEventType.ALLOCATION_CREATED, 4);
        seed(SKU_A, locationC, InventoryLedgerEventType.GOODS_RECEIPT, 9); // no allocations

        Map<UUID, Long> result =
                repository.calculateOutstandingAllocationsByLocation(List.of(locationA, locationB, locationC));

        // single-location semantics: sum(ALLOCATION_CREATED) - sum(ALLOCATION_RELEASED)
        assertThat(result).containsOnly(Map.entry(locationA, 6L), Map.entry(locationB, 4L));
        assertThat(result).doesNotContainKey(locationC);

        // cross-check against the same ledger entries the single-location path reads
        List<InventoryLedgerEntry> locationAEntries = repository.findByLocationIdAndEventTypeIn(
                locationA,
                List.of(InventoryLedgerEventType.ALLOCATION_CREATED, InventoryLedgerEventType.ALLOCATION_RELEASED));
        long created = locationAEntries.stream()
                .filter(entry -> entry.getEventType() == InventoryLedgerEventType.ALLOCATION_CREATED)
                .mapToLong(InventoryLedgerEntry::getChangeInQuantity)
                .sum();
        long released = locationAEntries.stream()
                .filter(entry -> entry.getEventType() == InventoryLedgerEventType.ALLOCATION_RELEASED)
                .mapToLong(InventoryLedgerEntry::getChangeInQuantity)
                .sum();
        assertThat(result.get(locationA)).isEqualTo(created - released);
    }
}
