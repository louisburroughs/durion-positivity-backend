package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The per-line derivations of #2635: value, open quantity, balance and status. */
@DisplayName("PurchaseOrderLineFigures — open, balance and status derive from the line figures (#2635)")
class PurchaseOrderLineFiguresTest {

    private static PurchaseOrderLineEntity line(String ordered, long lineTotalMinor) {
        return PurchaseOrderLineEntity.builder()
                .lineId(UUID.randomUUID())
                .lineNumber(1)
                .description("rotor")
                .quantityDecimal(new BigDecimal(ordered))
                .unitCostMinor(lineTotalMinor)
                .lineTotalMinor(lineTotalMinor)
                .build();
    }

    private static PurchaseOrderEntity order(PurchaseOrderStatus status, PurchaseOrderLineEntity... lines) {
        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .purchaseOrderId(UUID.randomUUID())
                .poNumber("PO-2635")
                .status(status)
                .currency("USD")
                .lines(new ArrayList<>(List.of(lines)))
                .build();
        for (PurchaseOrderLineEntity line : lines) {
            line.setPurchaseOrder(order);
        }
        return order;
    }

    @Test
    @DisplayName("a quantity's value is lineTotal × quantity ÷ ordered, HALF_UP to minor units")
    void valueIsTheAccruedUnitPriceApplied() {
        PurchaseOrderLineEntity three = line("3", 1_000L);
        assertThat(PurchaseOrderLineFigures.valueOf(three, BigDecimal.ONE)).isEqualTo(333L);
        assertThat(PurchaseOrderLineFigures.valueOf(three, new BigDecimal("2"))).isEqualTo(667L);
        assertThat(PurchaseOrderLineFigures.valueOf(three, new BigDecimal("3"))).isEqualTo(1_000L);
        assertThat(PurchaseOrderLineFigures.valueOf(line("0", 1_000L), BigDecimal.ONE))
                .isZero();
    }

    @Test
    @DisplayName("open = max(0, ordered − received − returned − cancelled)")
    void openFloorsAtZero() {
        PurchaseOrderLineEntity l = line("10", 10_000L);
        l.setReceivedQuantity(new BigDecimal("6"));
        l.setReturnedQuantity(new BigDecimal("1"));
        l.setCancelledQuantity(new BigDecimal("2"));
        assertThat(PurchaseOrderLineFigures.open(l)).isEqualByComparingTo("1");
        l.setReceivedQuantity(new BigDecimal("12"));
        assertThat(PurchaseOrderLineFigures.open(l)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("refresh derives each line's open quantity and the status; moveBalance never goes below zero")
    void refreshAndBalance() {
        PurchaseOrderLineEntity a = line("10", 10_000L);
        a.setReceivedQuantity(new BigDecimal("4"));
        a.setReceivedAccruedMinor(4_000L);
        PurchaseOrderLineEntity b = line("2", 5_000L);
        b.setCancelledQuantity(new BigDecimal("1"));
        b.setCancelledValueMinor(2_500L);
        PurchaseOrderEntity order = order(PurchaseOrderStatus.APPROVED, a, b);
        order.setOpenBalanceMinor(8_500L);

        PurchaseOrderLineFigures.refresh(order);
        assertThat(a.getOpenQuantityDecimal()).isEqualByComparingTo("6");
        assertThat(b.getOpenQuantityDecimal()).isEqualByComparingTo("1");
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(order.getOpenBalanceMinor())
                .as("refresh leaves the balance alone")
                .isEqualTo(8_500L);

        PurchaseOrderLineFigures.moveBalance(order, -10_000L);
        assertThat(order.getOpenBalanceMinor()).isZero();
        PurchaseOrderLineFigures.moveBalance(order, 2_000L);
        assertThat(order.getOpenBalanceMinor()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName(
            "a line written before the figures (open below ordered, figures zero) adopts received = ordered − open")
    void legacyOpenIsAdopted() {
        PurchaseOrderLineEntity legacy = line("10", 10_000L);
        legacy.setOpenQuantityDecimal(new BigDecimal("4"));
        PurchaseOrderEntity order = order(PurchaseOrderStatus.PARTIALLY_RECEIVED, legacy);

        PurchaseOrderLineFigures.refresh(order);

        assertThat(legacy.getReceivedQuantity()).isEqualByComparingTo("6");
        assertThat(legacy.getReceivedAccruedMinor()).isEqualTo(6_000L);
        assertThat(legacy.getOpenQuantityDecimal()).isEqualByComparingTo("4");
        // Once touched, the stored open quantity no longer drives anything.
        legacy.setOpenQuantityDecimal(BigDecimal.ONE);
        PurchaseOrderLineFigures.refresh(order);
        assertThat(legacy.getOpenQuantityDecimal()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName(
            "status: all settled → CLOSED with a short-close else FULLY_RECEIVED; received → PARTIALLY; else APPROVED")
    void statusMatrix() {
        PurchaseOrderLineEntity l = line("4", 4_000L);
        PurchaseOrderEntity order = order(PurchaseOrderStatus.PARTIALLY_RECEIVED, l);

        PurchaseOrderLineFigures.refresh(order);
        assertThat(order.getStatus()).as("nothing received: back to APPROVED").isEqualTo(PurchaseOrderStatus.APPROVED);

        l.setReceivedQuantity(new BigDecimal("4"));
        PurchaseOrderLineFigures.refresh(order);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.FULLY_RECEIVED);

        l.setReceivedQuantity(new BigDecimal("3"));
        l.setCancelledQuantity(BigDecimal.ONE);
        PurchaseOrderLineFigures.refresh(order);
        assertThat(order.getStatus())
                .as("settled with a short-closed remainder")
                .isEqualTo(PurchaseOrderStatus.CLOSED);

        l.setCancelledQuantity(BigDecimal.ZERO);
        l.setReturnedQuantity(BigDecimal.ONE);
        order.setStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        PurchaseOrderLineFigures.refresh(order);
        assertThat(order.getStatus())
                .as("a credited return settles the line too")
                .isEqualTo(PurchaseOrderStatus.FULLY_RECEIVED);
    }

    @Test
    @DisplayName("CANCELLED and CLOSED keep their status; the figures still move (#2634)")
    void terminalStatusesAreKept() {
        for (PurchaseOrderStatus terminal : List.of(PurchaseOrderStatus.CANCELLED, PurchaseOrderStatus.CLOSED)) {
            PurchaseOrderLineEntity l = line("4", 4_000L);
            l.setReceivedQuantity(new BigDecimal("4"));
            l.setReceivedAccruedMinor(4_000L);
            PurchaseOrderEntity order = order(terminal, l);
            PurchaseOrderLineFigures.refresh(order);
            assertThat(order.getStatus()).isEqualTo(terminal);
            assertThat(l.getOpenQuantityDecimal()).isEqualByComparingTo("0");
        }
    }

    @Test
    @DisplayName("toMinor converts a decimal at the currency exponent (JPY 0, USD 2, unknown → 2)")
    void toMinor() {
        assertThat(PurchaseOrderLineFigures.toMinor(new BigDecimal("12.345"), "USD"))
                .isEqualTo(1_235L);
        assertThat(PurchaseOrderLineFigures.toMinor(new BigDecimal("12.5"), "JPY"))
                .isEqualTo(13L);
        assertThat(PurchaseOrderLineFigures.toMinor(new BigDecimal("1.00"), "ZZZ"))
                .isEqualTo(100L);
        assertThat(PurchaseOrderLineFigures.toMinor(null, "USD")).isZero();
    }
}
