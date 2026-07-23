package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for the single ledger posting path and its
 * same-transaction stock summary maintenance (issue #1024, A1).
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("LedgerPostingService stock summary maintenance")
class LedgerPostingServiceImplTest {

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private InventoryStockSummaryRepository summaryRepository;

    private static String uniqueSku() {
        return "SKU-" + UUID.randomUUID();
    }

    private InventoryLedgerEntry entry(String sku, UUID locationId, InventoryLedgerEventType type, int change) {
        return InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(locationId)
                .eventType(type)
                .changeInQuantity(change)
                .quantityAfter(0)
                .transactionUserId("posting-test")
                .build();
    }

    @Test
    void post_onHandEvent_createsAndUpdatesSummaryRow() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();

        InventoryLedgerEntry first =
                ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, 10));
        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.GOODS_ISSUE, -4));

        InventoryStockSummary summary =
                summaryRepository.findByStockItemIdAndLocationId(sku, location).orElseThrow();
        assertThat(summary.getOnHand()).isEqualTo(6);
        assertThat(summary.getAllocated()).isZero();
        assertThat(summary.getAtp()).isEqualTo(6);
        assertThat(summary.getLastLedgerEntryId()).isNotNull();
        assertThat(summary.getLastLedgerEntryId()).isNotEqualTo(first.getLedgerEntryId());
        assertThat(summary.getLastEventAt()).isNotNull();
    }

    @Test
    void post_allocationEvents_updateAllocatedAndAtp() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();

        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, 100));
        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.ALLOCATION_CREATED, 30));
        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.ALLOCATION_RELEASED, 10));

        InventoryStockSummary summary =
                summaryRepository.findByStockItemIdAndLocationId(sku, location).orElseThrow();
        assertThat(summary.getOnHand()).isEqualTo(100);
        assertThat(summary.getAllocated()).isEqualTo(20);
        assertThat(summary.getAtp()).isEqualTo(80);
    }

    @Test
    void post_reservationEvents_updateReservedOnly() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();

        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, 50));
        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.RESERVATION_CREATED, 20));
        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.RESERVATION_RELEASED, 5));

        InventoryStockSummary summary =
                summaryRepository.findByStockItemIdAndLocationId(sku, location).orElseThrow();
        assertThat(summary.getOnHand()).isEqualTo(50);
        assertThat(summary.getAllocated()).isZero();
        assertThat(summary.getReserved()).isEqualTo(15);
        // ATP per ADR-0001 subtracts allocations only.
        assertThat(summary.getAtp()).isEqualTo(50);
    }

    @Test
    void post_trackingOnlyEvent_createsNoSummaryRow() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();

        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.BACKORDER_CREATED, 5));
        ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.PICK_TASK_CREATED, 5));

        assertThat(summaryRepository.findByStockItemIdAndLocationId(sku, location))
                .isEmpty();
    }

    @Test
    void post_nullLocationEntry_aggregatesOnNullLocationRow() {
        String sku = uniqueSku();

        // K1 (#1027): consumption may no longer drive on-hand negative, so seed
        // the null-location row before consuming from it.
        ledgerPostingService.post(entry(sku, null, InventoryLedgerEventType.GOODS_RECEIPT, 5));
        ledgerPostingService.post(entry(sku, null, InventoryLedgerEventType.WORKORDER_CONSUMPTION, -3));
        ledgerPostingService.post(entry(sku, null, InventoryLedgerEventType.RETURN_TO_STOCK, 1));

        InventoryStockSummary summary =
                summaryRepository.findByStockItemIdAndLocationIdIsNull(sku).orElseThrow();
        assertThat(summary.getOnHand()).isEqualTo(3);
    }

    @Test
    void postAll_appliesBatchDeltasPerKey_andReturnsEntriesInOrder() {
        String sku = uniqueSku();
        UUID locationA = UUID.randomUUID();
        UUID locationB = UUID.randomUUID();

        // K1 (#1027): TRANSFER_OUT is blocked below zero, so the batch seeds
        // location A before dispatching from it (exactly-to-zero is allowed).
        List<InventoryLedgerEntry> saved = ledgerPostingService.postAll(List.of(
                entry(sku, locationA, InventoryLedgerEventType.GOODS_RECEIPT, 5),
                entry(sku, locationA, InventoryLedgerEventType.TRANSFER_OUT, -5),
                entry(sku, locationB, InventoryLedgerEventType.TRANSFER_IN, 5),
                entry(sku, locationB, InventoryLedgerEventType.GOODS_RECEIPT, 2)));

        assertThat(saved).hasSize(4);
        assertThat(saved.getFirst().getLedgerEntryId()).isNotNull();
        assertThat(summaryRepository
                        .findByStockItemIdAndLocationId(sku, locationA)
                        .orElseThrow()
                        .getOnHand())
                .isZero();
        assertThat(summaryRepository
                        .findByStockItemIdAndLocationId(sku, locationB)
                        .orElseThrow()
                        .getOnHand())
                .isEqualTo(7);
    }
}
