package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.internal.entity.OrderDiscountType;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SalesOrderStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rounding-vector tests for {@link OrderTotalsCalculator} (parity story B2, #1067): the order
 * discount must allocate pro-rata with the residual corrected on the largest line so the
 * allocations sum exactly, for adversarial percentages.
 */
@DisplayName("OrderTotalsCalculator — parity story B2 (#1067)")
class OrderTotalsCalculatorTest {

    private final OrderTotalsCalculator calculator = new OrderTotalsCalculator();

    private static SalesOrder orderWithLines(BigDecimal... unitPrices) {
        SalesOrder order = SalesOrder.builder()
                .orderId(UUID.randomUUID())
                .clerkId("clerk-1")
                .terminalId("terminal-1")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .build();
        order.setLines(new ArrayList<>());
        for (BigDecimal unitPrice : unitPrices) {
            order.getLines()
                    .add(SalesOrderLine.builder()
                            .orderLineId(UUID.randomUUID())
                            .order(order)
                            .itemSku("SKU-" + unitPrice)
                            .itemDescription("item")
                            .quantity(1)
                            .unitPrice(unitPrice)
                            .build());
        }
        return order;
    }

    @Test
    @DisplayName("33.33% order discount across three equal lines sums exactly (residual on largest)")
    void percentDiscount_adversarialRounding_sumsExactly() {
        SalesOrder order =
                orderWithLines(new BigDecimal("33.3300"), new BigDecimal("33.3300"), new BigDecimal("33.3400"));
        order.setOrderDiscountType(OrderDiscountType.PERCENT);
        order.setOrderDiscountValue(new BigDecimal("33.33"));

        calculator.recalculate(order);

        BigDecimal expectedDiscount = new BigDecimal("100.0000")
                .multiply(new BigDecimal("33.33"))
                .divide(new BigDecimal("100"), 4, java.math.RoundingMode.HALF_UP);
        BigDecimal allocated = order.getLines().stream()
                .map(SalesOrderLine::getDiscountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(allocated).isEqualByComparingTo(expectedDiscount);
        assertThat(order.getDiscountTotal()).isEqualByComparingTo(expectedDiscount);
        assertThat(order.getSubtotal()).isEqualByComparingTo("100.0000");
        assertThat(order.getGrandTotal())
                .isEqualByComparingTo(order.getSubtotal().subtract(expectedDiscount));
        // Σ lineTotal must equal grandTotal exactly (no lost or invented cents)
        BigDecimal lineTotals =
                order.getLines().stream().map(SalesOrderLine::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lineTotals).isEqualByComparingTo(order.getGrandTotal());
    }

    @Test
    @DisplayName("AMOUNT discount is capped at the subtotal and allocated exactly")
    void amountDiscount_cappedAndExact() {
        SalesOrder order = orderWithLines(new BigDecimal("10.0000"), new BigDecimal("5.0000"));
        order.setOrderDiscountType(OrderDiscountType.AMOUNT);
        order.setOrderDiscountValue(new BigDecimal("999.0000"));

        calculator.recalculate(order);

        assertThat(order.getDiscountTotal()).isEqualByComparingTo("15.0000");
        assertThat(order.getGrandTotal()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("Line discount percent reduces lineSubtotal and rides discountTotal")
    void lineDiscount_combinesWithOrderDiscount() {
        SalesOrder order = orderWithLines(new BigDecimal("100.0000"));
        order.getLines().get(0).setDiscountPercent(new BigDecimal("10"));
        order.setOrderDiscountType(OrderDiscountType.AMOUNT);
        order.setOrderDiscountValue(new BigDecimal("5.0000"));

        calculator.recalculate(order);

        assertThat(order.getLines().get(0).getLineSubtotal()).isEqualByComparingTo("90.0000");
        assertThat(order.getSubtotal()).isEqualByComparingTo("90.0000");
        assertThat(order.getLines().get(0).getDiscountAmount()).isEqualByComparingTo("15.0000");
        assertThat(order.getDiscountTotal()).isEqualByComparingTo("15.0000");
        assertThat(order.getGrandTotal()).isEqualByComparingTo("85.0000");
    }

    @Test
    @DisplayName("No discount: subtotal = grandTotal (+ tax) and totals are stable")
    void noDiscount_totalsStable() {
        SalesOrder order = orderWithLines(new BigDecimal("19.9900"), new BigDecimal("5.0100"));
        order.getLines().get(0).setTaxAmount(new BigDecimal("1.6000"));

        calculator.recalculate(order);

        assertThat(order.getSubtotal()).isEqualByComparingTo("25.0000");
        assertThat(order.getTaxTotal()).isEqualByComparingTo("1.6000");
        assertThat(order.getGrandTotal()).isEqualByComparingTo("26.6000");
        assertThat(order.getLines().get(0).getLineTotal()).isEqualByComparingTo("21.5900");
    }
}
