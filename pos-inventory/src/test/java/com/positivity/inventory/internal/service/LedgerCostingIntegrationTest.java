package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.SkuCostState;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Funnel wiring for method-derived costing (odoo-parity J1, issue #1048): the
 * single ledger posting path stamps a method-derived {@code unitCost} on every
 * on-hand-affecting entry and maintains {@code sku_cost_state}, in batch order,
 * in the same transaction. SKUs with no config default to AVERAGE.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Ledger posting funnel — method-derived cost stamping")
class LedgerCostingIntegrationTest {

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private SkuCostStateRepository costStateRepository;

    private static String uniqueSku() {
        return "SKU-" + UUID.randomUUID();
    }

    private InventoryLedgerEntry entry(
            String sku, UUID location, InventoryLedgerEventType type, int change, BigDecimal unitCost) {
        return InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(location)
                .eventType(type)
                .changeInQuantity(BigDecimal.valueOf(change))
                .quantityAfter(new BigDecimal("0"))
                .unitCost(unitCost)
                .transactionUserId("costing-test")
                .build();
    }

    @Test
    @DisplayName("batch order: receipts update running cost first, then the issue stamps it (AVCO default)")
    void batchOrder_receiptThenIssue_stampsAverage() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();

        List<InventoryLedgerEntry> posted = ledgerPostingService.postAll(List.of(
                entry(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, 10, new BigDecimal("5")),
                entry(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, 10, new BigDecimal("7")),
                entry(sku, location, InventoryLedgerEventType.GOODS_ISSUE, -5, null)));

        // receipts stamped with their document costs; issue stamped at running avg 6
        assertThat(posted.get(0).getUnitCost()).isEqualByComparingTo("5");
        assertThat(posted.get(1).getUnitCost()).isEqualByComparingTo("7");
        assertThat(posted.get(2).getUnitCost()).isEqualByComparingTo("6");

        SkuCostState state = costStateRepository.findByStockItemId(sku).orElseThrow();
        assertThat(state.getAvgCost()).isEqualByComparingTo("6");
        assertThat(state.getOnHandQty()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("first issue for a SKU received without a document cost stamps null (matches pre-J1 behaviour)")
    void firstIssue_noPriorCost_stampsNull() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();

        // Receive stock but with no document unit cost, so the running average stays unseeded.
        List<InventoryLedgerEntry> posted = ledgerPostingService.postAll(List.of(
                entry(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, 10, null),
                entry(sku, location, InventoryLedgerEventType.GOODS_ISSUE, -4, null)));

        assertThat(posted.get(0).getUnitCost()).isNull();
        assertThat(posted.get(1).getUnitCost()).isNull();
    }

    @Test
    @DisplayName("allocation/reservation entries are never costed")
    void nonOnHandAffecting_notCosted() {
        String sku = uniqueSku();
        UUID location = UUID.randomUUID();

        ledgerPostingService.post(
                entry(sku, location, InventoryLedgerEventType.GOODS_RECEIPT, 100, new BigDecimal("3")));
        InventoryLedgerEntry allocation =
                ledgerPostingService.post(entry(sku, location, InventoryLedgerEventType.ALLOCATION_CREATED, 10, null));

        assertThat(allocation.getUnitCost()).isNull();
        // the running cost state reflects only the receipt
        SkuCostState state = costStateRepository.findByStockItemId(sku).orElseThrow();
        assertThat(state.getOnHandQty()).isEqualByComparingTo("100");
        assertThat(state.getAvgCost()).isEqualByComparingTo("3");
    }
}
