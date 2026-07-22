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
    void verifier_reportsMissingSummaryRowForLedgerBalance() {
        String sku = "SKU-MISSING-" + UUID.randomUUID();
        UUID location = UUID.randomUUID();
        post(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, 5);

        summaryRepository.deleteAll();

        assertThat(driftVerifier.verify()).isEqualTo(1);
    }
}
