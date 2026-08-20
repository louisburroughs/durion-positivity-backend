package com.positivity.inventory.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import java.math.BigDecimal;
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
                .changeInQuantity(BigDecimal.valueOf(changeInQuantity))
                .quantityAfter(new BigDecimal("0"))
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
        Map<UUID, BigDecimal> result =
                repository.sumQuantityByLocationChunked(List.of(locationA, locationB, locationC), onHandTypes);

        assertThat(result).hasSize(2);
        assertThat(result.get(locationA)).isEqualByComparingTo("6");
        assertThat(result.get(locationB)).isEqualByComparingTo("5");
        assertThat(result).doesNotContainKey(locationC);

        // grouped result matches the existing single-location aggregate for the same data
        assertThat(result.get(locationA))
                .isEqualByComparingTo(repository.calculateOnHandQuantityAtLocation(locationA, onHandTypes));
        assertThat(result.get(locationB))
                .isEqualByComparingTo(repository.calculateOnHandQuantityAtLocation(locationB, onHandTypes));
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

        Map<UUID, BigDecimal> result = repository.sumQuantityByLocationForSkuChunked(
                SKU_A, List.of(locationA, locationB), InventoryLedgerEventType.onHandAffectingTypes());

        assertThat(result).hasSize(2);
        assertThat(result.get(locationA)).isEqualByComparingTo("7");
        assertThat(result.get(locationB)).isEqualByComparingTo("2");
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

        Map<UUID, BigDecimal> result =
                repository.sumQuantityByLocationChunked(locationIds, InventoryLedgerEventType.onHandAffectingTypes());

        assertThat(result).hasSize(2);
        assertThat(result.get(firstLocation)).isEqualByComparingTo("11");
        assertThat(result.get(lastLocation)).isEqualByComparingTo("20");
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

        Map<UUID, BigDecimal> result =
                repository.calculateOutstandingAllocationsByLocation(List.of(locationA, locationB, locationC));

        // single-location semantics: sum(ALLOCATION_CREATED) - sum(ALLOCATION_RELEASED)
        assertThat(result).hasSize(2);
        assertThat(result.get(locationA)).isEqualByComparingTo("6");
        assertThat(result.get(locationB)).isEqualByComparingTo("4");
        assertThat(result).doesNotContainKey(locationC);

        // cross-check against the same ledger entries the single-location path reads
        List<InventoryLedgerEntry> locationAEntries = repository.findByLocationIdAndEventTypeIn(
                locationA,
                List.of(InventoryLedgerEventType.ALLOCATION_CREATED, InventoryLedgerEventType.ALLOCATION_RELEASED));
        BigDecimal created = locationAEntries.stream()
                .filter(entry -> entry.getEventType() == InventoryLedgerEventType.ALLOCATION_CREATED)
                .map(InventoryLedgerEntry::getChangeInQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal released = locationAEntries.stream()
                .filter(entry -> entry.getEventType() == InventoryLedgerEventType.ALLOCATION_RELEASED)
                .map(InventoryLedgerEntry::getChangeInQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(result.get(locationA)).isEqualByComparingTo(created.subtract(released));
    }
}
