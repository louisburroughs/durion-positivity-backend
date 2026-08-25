package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.math.BigDecimal;
import java.time.Instant;
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

    @Autowired
    private InventoryLotRepository lotRepository;

    private static String uniqueSku() {
        return "SKU-" + UUID.randomUUID();
    }

    private InventoryLedgerEntry entry(String sku, UUID locationId, InventoryLedgerEventType type, int change) {
        return InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(locationId)
                .eventType(type)
                .changeInQuantity(BigDecimal.valueOf(change))
                .quantityAfter(new BigDecimal("0"))
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
        assertThat(summary.getOnHand()).isEqualByComparingTo("6");
        assertThat(summary.getAllocated()).isZero();
        assertThat(summary.getAtp()).isEqualByComparingTo("6");
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
        assertThat(summary.getOnHand()).isEqualByComparingTo("100");
        assertThat(summary.getAllocated()).isEqualByComparingTo("20");
        assertThat(summary.getAtp()).isEqualByComparingTo("80");
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
        assertThat(summary.getOnHand()).isEqualByComparingTo("50");
        assertThat(summary.getAllocated()).isZero();
        assertThat(summary.getReserved()).isEqualByComparingTo("15");
        // ATP per ADR-0001 subtracts allocations only.
        assertThat(summary.getAtp()).isEqualByComparingTo("50");
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
        assertThat(summary.getOnHand()).isEqualByComparingTo("3");
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
                .isEqualByComparingTo("7");
    }

    /**
     * E2 lot-reconciliation trigger set (odoo-parity E2, #1042): only lots touched by an
     * on-hand-affecting entry are handed to {@code InventoryLotStatusReconciler}.
     *
     * <p>Two seeded lots, opposite starting status, in one batch: the {@code GOODS_RECEIPT}
     * lot starts {@code CONSUMED} with zero stock — reconciliation flips it {@code ACTIVE} once
     * the receipt lands, proving the on-hand-affecting entry reached the reconciler. The
     * {@code ALLOCATION_CREATED} lot starts {@code ACTIVE} with zero stock, which already
     * satisfies the reconciler's {@code ACTIVE -> CONSUMED} rule — if the allocation entry were
     * (wrongly) treated as on-hand-affecting, this lot would flip too. It must not: asserting it
     * stays {@code ACTIVE} pins that {@code affectsOnHand() == false} keeps a lot out of the
     * touched set even when the entry carries a {@code lotId}.
     *
     * <p>Not pinned here: {@code touchesLotOnHand}'s {@code entry.getEventType() == null} arm.
     * Every posting path that reaches {@code postAll} sets {@code eventType} (it drives the
     * summary delta computed earlier in the same method), so a saved entry with a null event
     * type is not a case this service's own callers can produce — left uncovered rather than
     * faked with a hand-built entry no real caller would send.
     */
    @Test
    void postAll_onlyOnHandAffectingLotEntriesReachLotReconciliation() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();
        Instant now = Instant.now();

        InventoryLot receivingLot = lotRepository.save(InventoryLot.builder()
                .stockItemId(sku)
                .lotNumber("LOT-RECV-" + UUID.randomUUID())
                .receivedAt(now)
                .status(InventoryLotStatus.CONSUMED)
                .build());
        InventoryLot allocationOnlyLot = lotRepository.save(InventoryLot.builder()
                .stockItemId(sku)
                .lotNumber("LOT-ALLOC-" + UUID.randomUUID())
                .receivedAt(now)
                .status(InventoryLotStatus.ACTIVE)
                .build());

        ledgerPostingService.postAll(List.of(
                InventoryLedgerEntry.builder()
                        .stockItemId(sku)
                        .locationId(location)
                        .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                        .changeInQuantity(BigDecimal.TEN)
                        .quantityAfter(BigDecimal.TEN)
                        .transactionUserId("posting-test")
                        .lotId(receivingLot.getLotId())
                        .build(),
                InventoryLedgerEntry.builder()
                        .stockItemId(sku)
                        .locationId(location)
                        .eventType(InventoryLedgerEventType.ALLOCATION_CREATED)
                        .changeInQuantity(BigDecimal.valueOf(4))
                        .quantityAfter(BigDecimal.ZERO)
                        .transactionUserId("posting-test")
                        .lotId(allocationOnlyLot.getLotId())
                        .build()));

        assertThat(lotRepository.findById(receivingLot.getLotId()).orElseThrow().getStatus())
                .isEqualTo(InventoryLotStatus.ACTIVE);
        assertThat(lotRepository
                        .findById(allocationOnlyLot.getLotId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(InventoryLotStatus.ACTIVE);
    }
}
