package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rebuild + drift-verifier tests (issue #1024, A1): the ledger is the source
 * of truth; a rebuild reconstructs the summary exactly and the verifier
 * reports (but never repairs) mismatches.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Stock summary rebuild and drift verification")
class StockSummaryRebuildAndDriftTest {

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private StockSummaryRebuildService rebuildService;

    @Autowired
    private StockSummaryDriftVerifier driftVerifier;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private InventoryStockSummaryRepository summaryRepository;

    @BeforeEach
    void cleanSlate() {
        // These tests assert whole-table invariants; start from empty tables.
        summaryRepository.deleteAll();
        ledgerRepository.deleteAll();
    }

    private void post(String sku, UUID location, InventoryLedgerEventType type, int change) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(location)
                .eventType(type)
                .changeInQuantity(change)
                .quantityAfter(0)
                .transactionUserId("rebuild-test")
                .build());
    }

    @Test
    void rebuild_reconstructsSummaryIdenticallyFromLedger() {
        String skuA = "SKU-A-" + UUID.randomUUID();
        String skuB = "SKU-B-" + UUID.randomUUID();
        UUID loc1 = UUID.randomUUID();
        UUID loc2 = UUID.randomUUID();

        post(skuA, loc1, InventoryLedgerEventType.GOODS_RECEIPT, 40);
        post(skuA, loc1, InventoryLedgerEventType.ALLOCATION_CREATED, 15);
        post(skuA, loc2, InventoryLedgerEventType.TRANSFER_IN, 7);
        post(skuB, loc1, InventoryLedgerEventType.GOODS_RECEIPT, 3);
        // K1 (#1027): consumption may not drive on-hand negative — seed the
        // null-location key before consuming from it.
        post(skuB, null, InventoryLedgerEventType.GOODS_RECEIPT, 6);
        post(skuB, null, InventoryLedgerEventType.WORKORDER_CONSUMPTION, -2);
        post(skuB, loc1, InventoryLedgerEventType.RESERVATION_CREATED, 1);

        var liveRows = summaryRepository.findAll();

        int rebuilt = rebuildService.rebuildFromLedger();

        assertThat(rebuilt).isEqualTo(liveRows.size());
        for (InventoryStockSummary live : liveRows) {
            InventoryStockSummary rebuiltRow = (live.getLocationId() == null
                            ? summaryRepository.findByStockItemIdAndLocationIdIsNull(live.getStockItemId())
                            : summaryRepository.findByStockItemIdAndLocationId(
                                    live.getStockItemId(), live.getLocationId()))
                    .orElseThrow();
            assertThat(rebuiltRow.getOnHand()).isEqualTo(live.getOnHand());
            assertThat(rebuiltRow.getAllocated()).isEqualTo(live.getAllocated());
            assertThat(rebuiltRow.getReserved()).isEqualTo(live.getReserved());
            assertThat(rebuiltRow.getAtp()).isEqualTo(live.getAtp());
            assertThat(rebuiltRow.getLastLedgerEntryId()).isEqualTo(live.getLastLedgerEntryId());
        }

        assertThat(driftVerifier.verify()).isZero();
    }

    @Test
    void verifier_reportsDriftWithoutMutating() {
        String sku = "SKU-DRIFT-" + UUID.randomUUID();
        UUID location = UUID.randomUUID();
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, 25);

        assertThat(driftVerifier.verify()).isZero();

        // Corrupt the summary out-of-band (simulating a bypassing write).
        InventoryStockSummary row =
                summaryRepository.findByStockItemIdAndLocationId(sku, location).orElseThrow();
        row.setOnHand(999);
        summaryRepository.save(row);

        int drifted = driftVerifier.verify();
        assertThat(drifted).isEqualTo(1);

        // Report-only: the corrupted value must still be there.
        assertThat(summaryRepository
                        .findByStockItemIdAndLocationId(sku, location)
                        .orElseThrow()
                        .getOnHand())
                .isEqualTo(999);

        // Repair path is the rebuild.
        rebuildService.rebuildFromLedger();
        assertThat(summaryRepository
                        .findByStockItemIdAndLocationId(sku, location)
                        .orElseThrow()
                        .getOnHand())
                .isEqualTo(25);
        assertThat(driftVerifier.verify()).isZero();
    }

    @Test
    void rebuild_and_verifier_reconstructInTransitFromTransferShape() {
        // odoo-parity C2 (#1036): in-transit is derived from ledger shape — TRANSFER_OUT
        // contributes +qty at its DESTINATION (toLocationId) key, TRANSFER_IN drains it.
        String sku = "SKU-TRANSIT-" + UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID destination = UUID.randomUUID();

        post(sku, source, InventoryLedgerEventType.GOODS_RECEIPT, 10);
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(source)
                .fromLocationId(source)
                .toLocationId(destination)
                .eventType(InventoryLedgerEventType.TRANSFER_OUT)
                .changeInQuantity(-6)
                .quantityAfter(4)
                .transactionUserId("rebuild-test")
                .build());
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(destination)
                .fromLocationId(source)
                .toLocationId(destination)
                .eventType(InventoryLedgerEventType.TRANSFER_IN)
                .changeInQuantity(2)
                .quantityAfter(2)
                .transactionUserId("rebuild-test")
                .build());

        InventoryStockSummary liveDestination = summaryRepository
                .findByStockItemIdAndLocationId(sku, destination)
                .orElseThrow();
        assertThat(liveDestination.getOnHand()).isEqualTo(2);
        assertThat(liveDestination.getInTransitQty()).isEqualTo(4);
        assertThat(driftVerifier.verify()).isZero();

        rebuildService.rebuildFromLedger();

        InventoryStockSummary rebuiltDestination = summaryRepository
                .findByStockItemIdAndLocationId(sku, destination)
                .orElseThrow();
        assertThat(rebuiltDestination.getOnHand()).isEqualTo(2);
        assertThat(rebuiltDestination.getInTransitQty()).isEqualTo(4);
        InventoryStockSummary rebuiltSource =
                summaryRepository.findByStockItemIdAndLocationId(sku, source).orElseThrow();
        assertThat(rebuiltSource.getOnHand()).isEqualTo(4);
        assertThat(rebuiltSource.getInTransitQty()).isZero();
        assertThat(driftVerifier.verify()).isZero();

        // Out-of-band in-transit corruption is drift like any other column.
        rebuiltDestination.setInTransitQty(999);
        summaryRepository.save(rebuiltDestination);
        assertThat(driftVerifier.verify()).isEqualTo(1);
    }

    @Test
    void verifier_reportsMissingSummaryRowForLedgerBalance() {
        String sku = "SKU-MISSING-" + UUID.randomUUID();
        UUID location = UUID.randomUUID();
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, 5);

        summaryRepository.deleteAll();

        assertThat(driftVerifier.verify()).isEqualTo(1);
    }
}
