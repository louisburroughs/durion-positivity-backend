package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.service.CostingStrategy.CostState;
import com.positivity.inventory.internal.service.CostingStrategy.CostingInput;
import com.positivity.inventory.internal.service.CostingStrategy.CostingResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Standard costing behaviour (odoo-parity J1, issue #1048; ADR-0048). */
@DisplayName("StandardCostingStrategy")
class StandardCostingStrategyTest {

    private final StandardCostingStrategy strategy = new StandardCostingStrategy();

    private static final String SKU = "SKU-STD";

    @Test
    @DisplayName("method() is STANDARD")
    void method_isStandard() {
        assertThat(strategy.method()).isEqualTo(CostingMethod.STANDARD);
    }

    @Test
    @DisplayName("stamps the configured standard price; receipts do not change it")
    void stampsStandardPrice_receiptDoesNotChangeIt() {
        CostState state = new CostState(null, new BigDecimal("0"), new BigDecimal("12.5"));
        CostingResult receipt = strategy.cost(new CostingInput(
                SKU, InventoryLedgerEventType.GOODS_RECEIPT, new BigDecimal("10"), new BigDecimal("9"), state));
        assertThat(receipt.unitCost()).isEqualByComparingTo("12.5");
        assertThat(receipt.state().standardCost()).isEqualByComparingTo("12.5");
        // latest-receipt-cost memo is remembered but never overrides the standard price
        assertThat(receipt.state().avgCost()).isEqualByComparingTo("9");

        CostingResult issue = strategy.cost(new CostingInput(
                SKU, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-4"), null, receipt.state()));
        assertThat(issue.unitCost()).isEqualByComparingTo("12.5");
    }

    @Test
    @DisplayName("no standard price: falls back to latest receipt cost, then to null")
    void noStandardPrice_fallsBackToLatestReceiptCostThenNull() {
        // First issue with no prior receipt and no standard -> uncosted (null).
        CostingResult firstIssue = strategy.cost(new CostingInput(
                SKU,
                InventoryLedgerEventType.GOODS_ISSUE,
                new BigDecimal("-2"),
                null,
                new CostState(null, new BigDecimal("0"), null)));
        assertThat(firstIssue.unitCost()).isNull();

        // A receipt records the latest receipt cost memo.
        CostingResult receipt = strategy.cost(new CostingInput(
                SKU,
                InventoryLedgerEventType.GOODS_RECEIPT,
                new BigDecimal("10"),
                new BigDecimal("7.25"),
                firstIssue.state()));
        assertThat(receipt.unitCost()).isEqualByComparingTo("7.25");

        // Subsequent issue with still no standard price falls back to that latest receipt cost.
        CostingResult issue = strategy.cost(new CostingInput(
                SKU, InventoryLedgerEventType.GOODS_ISSUE, new BigDecimal("-3"), null, receipt.state()));
        assertThat(issue.unitCost()).isEqualByComparingTo("7.25");
    }
}
