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

/**
 * AVCO (weighted-average) costing vectors ported from the Odoo
 * {@code _update_standard_price} reference (odoo-parity J1, issue #1048;
 * ADR-0048).
 */
@DisplayName("AverageCostingStrategy (AVCO)")
class AverageCostingStrategyTest {

    private final AverageCostingStrategy strategy = new AverageCostingStrategy();

    private static final String SKU = "SKU-AVCO";

    private CostingResult receipt(CostState state, int qty, String unitCost) {
        return strategy.cost(new CostingInput(
                SKU, InventoryLedgerEventType.GOODS_RECEIPT, BigDecimal.valueOf(qty), new BigDecimal(unitCost), state));
    }

    private CostingResult issue(CostState state, int qty) {
        return strategy.cost(
                new CostingInput(SKU, InventoryLedgerEventType.GOODS_ISSUE, BigDecimal.valueOf(-qty), null, state));
    }

    @Test
    @DisplayName("method() is AVERAGE")
    void method_isAverage() {
        assertThat(strategy.method()).isEqualTo(CostingMethod.AVERAGE);
    }

    @Test
    @DisplayName("ported Odoo AVCO vector: recv 10@5, recv 10@7 => avg 6; issue 5 => cost 6; recv 10@9 => avg 7.20")
    void avcoVector() {
        // recv 10 @ $5 -> avg seeds to 5, on-hand 10
        CostingResult r1 = receipt(new CostState(null, new BigDecimal("0"), null), 10, "5");
        assertThat(r1.unitCost()).isEqualByComparingTo("5");
        assertThat(r1.state().avgCost()).isEqualByComparingTo("5");
        assertThat(r1.state().onHandQty()).isEqualByComparingTo("10");

        // recv 10 @ $7 -> avg (10*5 + 10*7)/20 = 6, on-hand 20
        CostingResult r2 = receipt(r1.state(), 10, "7");
        assertThat(r2.unitCost()).isEqualByComparingTo("7");
        assertThat(r2.state().avgCost()).isEqualByComparingTo("6");
        assertThat(r2.state().onHandQty()).isEqualByComparingTo("20");

        // issue 5 -> stamped at current avg 6, avg unchanged, on-hand 15
        CostingResult r3 = issue(r2.state(), 5);
        assertThat(r3.unitCost()).isEqualByComparingTo("6");
        assertThat(r3.state().avgCost()).isEqualByComparingTo("6");
        assertThat(r3.state().onHandQty()).isEqualByComparingTo("15");

        // recv 10 @ $9 (15 on hand) -> avg (15*6 + 10*9)/25 = 180/25 = 7.20, on-hand 25.
        // NOTE: the story text wrote "$6.60" for this step, but (15*6 + 10*9)/25 = 7.20
        // arithmetically; the implemented AVCO uses the correct weighted average.
        CostingResult r4 = receipt(r3.state(), 10, "9");
        assertThat(r4.unitCost()).isEqualByComparingTo("9");
        assertThat(r4.state().avgCost()).isEqualByComparingTo("7.20");
        assertThat(r4.state().onHandQty()).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("first issue with no prior cost stamps null (visibly uncosted)")
    void firstIssueNoPriorCost_stampsNull() {
        CostingResult r = issue(new CostState(null, new BigDecimal("0"), null), 3);
        assertThat(r.unitCost()).isNull();
        assertThat(r.state().avgCost()).isNull();
        assertThat(r.state().onHandQty()).isEqualByComparingTo("-3");
    }

    @Test
    @DisplayName("negative branch: issue when on-hand already <= 0 keeps the last average")
    void negativeBranch_issueBelowZero_keepsLastAverage() {
        // Seed an average, then drive on-hand negative via successive issues.
        CostState seeded = new CostState(new BigDecimal("4.50"), new BigDecimal("2"), null);
        CostingResult afterFirst = issue(seeded, 5); // on-hand 2 -> -3
        assertThat(afterFirst.unitCost()).isEqualByComparingTo("4.50");
        assertThat(afterFirst.state().avgCost()).isEqualByComparingTo("4.50");
        assertThat(afterFirst.state().onHandQty()).isEqualByComparingTo("-3");

        // Another issue while already negative still stamps and keeps the last average.
        CostingResult afterSecond = issue(afterFirst.state(), 2);
        assertThat(afterSecond.unitCost()).isEqualByComparingTo("4.50");
        assertThat(afterSecond.state().avgCost()).isEqualByComparingTo("4.50");
        assertThat(afterSecond.state().onHandQty()).isEqualByComparingTo("-5");
    }

    @Test
    @DisplayName("receipt onto non-positive on-hand reseeds the average from the receipt cost")
    void receiptOntoNonPositiveOnHand_reseedsAverage() {
        CostState negative = new CostState(new BigDecimal("4.50"), new BigDecimal("-5"), null);
        CostingResult r = receipt(negative, 10, "8");
        assertThat(r.unitCost()).isEqualByComparingTo("8");
        assertThat(r.state().avgCost()).isEqualByComparingTo("8");
        assertThat(r.state().onHandQty()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("cost-less inbound enters at the current average, quantity rises, average unchanged")
    void costlessInbound_entersAtCurrentAverage() {
        CostState state = new CostState(new BigDecimal("6"), new BigDecimal("10"), null);
        CostingResult r = strategy.cost(
                new CostingInput(SKU, InventoryLedgerEventType.ADJUSTMENT_IN, new BigDecimal("5"), null, state));
        assertThat(r.unitCost()).isEqualByComparingTo("6");
        assertThat(r.state().avgCost()).isEqualByComparingTo("6");
        assertThat(r.state().onHandQty()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("stamped cost is rounded to the ledger scale of 4")
    void stampRoundedToScaleFour() {
        // Force a repeating-decimal average: recv 3 @ 10 then recv 0-less inbound won't help;
        // use recv 1@10 then recv 2@11 -> (10 + 22)/3 = 10.666666...
        CostingResult r1 = receipt(new CostState(null, new BigDecimal("0"), null), 1, "10");
        CostingResult r2 = receipt(r1.state(), 2, "11");
        // avg = 32/3 = 10.666667 (scale 6). An issue stamps that rounded to scale 4 = 10.6667.
        CostingResult issue = issue(r2.state(), 1);
        assertThat(issue.unitCost()).isEqualByComparingTo("10.6667");
        assertThat(issue.unitCost().scale()).isEqualTo(4);
    }
}
