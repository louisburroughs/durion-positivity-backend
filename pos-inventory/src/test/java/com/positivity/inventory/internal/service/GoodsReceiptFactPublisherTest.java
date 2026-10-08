package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.inventory.GoodsReceiptLine;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The receipt fact's cost basis (CAP:550 S41 #2602, #2598): each line's value, method and ledger row come from the
 * posted {@code GOODS_RECEIPT} row, in the order's currency, HALF_UP per line; the total is the sum of the lines.
 */
@DisplayName("GoodsReceiptFactPublisher — goodsreceipt.recorded cost basis (S41 #2602)")
class GoodsReceiptFactPublisherTest {

    private static final UUID RECEIPT_ID = UUID.fromString("019a0000-0000-7000-8000-0000000000e1");
    private static final UUID ORDER_ID = UUID.fromString("019a0000-0000-7000-8000-0000000000e2");
    private static final UUID PO_LINE = UUID.fromString("019a0000-0000-7000-8000-0000000000e3");
    private static final UUID PRODUCT = UUID.fromString("019a0000-0000-7000-8000-0000000000e4");
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    private OutboxEventWriter writer;
    private CostingMethodResolver methods;
    private GoodsReceiptFactPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        writer = mock(OutboxEventWriter.class);
        ObjectProvider<OutboxEventWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(writer);
        methods = mock(CostingMethodResolver.class);
        stubMethod(CostingMethod.AVERAGE);
        publisher = new GoodsReceiptFactPublisher(
                provider, Clock.fixed(NOW, ZoneOffset.UTC), methods, new ReceiptCostCurrencyPolicy("USD"));
    }

    @Test
    @DisplayName("AC12: 2.5 at 1.0100 under AVERAGE is valued 2.53 HALF_UP, beside its 2.53 accrual, in USD")
    void valuesTheLineAtTheRowsCostHalfUp() {
        InventoryLedgerEntry row = row(UUID.randomUUID(), "1.0100");
        UUID receiptLine = UUID.randomUUID();

        GoodsReceiptRecordedV1 fact = publish("USD", fact(receiptLine, "2.5", 253L, row));

        assertThat(fact.currencyCode()).isEqualTo("USD");
        assertThat(fact.totalAccruedAmountMinor()).isEqualTo(253L);
        GoodsReceiptLine line = fact.lines().getFirst();
        assertThat(line.inventoryValueMinor()).isEqualTo(253L);
        assertThat(line.accruedAmountMinor()).isEqualTo(253L);
        assertThat(line.costSource()).isEqualTo("AVERAGE");
        assertThat(line.receiptLineId()).isEqualTo(receiptLine);
        assertThat(line.ledgerEntryId()).isEqualTo(row.getLedgerEntryId());
        assertThat(line.productId()).isEqualTo(PRODUCT);
        assertThat(line.poLineId()).isEqualTo(PO_LINE);
    }

    @Test
    @DisplayName("STANDARD: the value is the standard cost the row carries, not the accrual; the method is named")
    void standardCostValueDiffersFromTheAccrual() {
        stubMethod(CostingMethod.STANDARD);

        GoodsReceiptRecordedV1 fact =
                publish("USD", fact(UUID.randomUUID(), "4", 40_000L, row(UUID.randomUUID(), "95.0000")));

        assertThat(fact.lines().getFirst().inventoryValueMinor()).isEqualTo(38_000L);
        assertThat(fact.lines().getFirst().accruedAmountMinor()).isEqualTo(40_000L);
        assertThat(fact.lines().getFirst().costSource()).isEqualTo("STANDARD");
    }

    @Test
    @DisplayName("an uncosted row: costSource NONE and no value, never 0")
    void uncostedRowStatesNoValue() {
        GoodsReceiptRecordedV1 fact = publish("USD", fact(UUID.randomUUID(), "4", 0L, row(UUID.randomUUID(), null)));

        assertThat(fact.lines().getFirst().inventoryValueMinor()).isNull();
        assertThat(fact.lines().getFirst().costSource()).isEqualTo("NONE");
        assertThat(fact.lines().getFirst().ledgerEntryId()).isNotNull();
    }

    @Test
    @DisplayName("an order outside the functional currency states its currency and no value (ADR-0067 DF-6)")
    void foreignOrderStatesNoValue() {
        GoodsReceiptRecordedV1 fact =
                publish("EUR", fact(UUID.randomUUID(), "4", 40_000L, row(UUID.randomUUID(), "12.0000")));

        assertThat(fact.currencyCode()).isEqualTo("EUR");
        assertThat(fact.lines().getFirst().inventoryValueMinor()).isNull();
    }

    @Test
    @DisplayName("an order with no currency is published without one, never defaulted (ADR-0067 R-2)")
    void currencylessOrderIsNeverDefaulted() {
        GoodsReceiptRecordedV1 fact =
                publish(null, fact(UUID.randomUUID(), "4", 40_000L, row(UUID.randomUUID(), "12.0000")));

        assertThat(fact.currencyCode()).isNull();
        assertThat(fact.lines().getFirst().inventoryValueMinor()).isNull();
    }

    @Test
    @DisplayName("invariant: totalAccruedAmountMinor is the sum of the line accruals")
    void totalIsTheSumOfTheLines() {
        GoodsReceiptRecordedV1 fact = publish(
                "USD",
                fact(UUID.randomUUID(), "2.5", 253L, row(UUID.randomUUID(), "1.0100")),
                fact(UUID.randomUUID(), "4", 40_000L, row(UUID.randomUUID(), "100.0000")),
                fact(UUID.randomUUID(), "1", 0L, row(UUID.randomUUID(), "150.0000")));

        assertThat(fact.totalAccruedAmountMinor())
                .isEqualTo(fact.lines().stream()
                        .mapToLong(GoodsReceiptLine::accruedAmountMinor)
                        .sum())
                .isEqualTo(40_253L);
        verify(methods, org.mockito.Mockito.times(1)).resolveAll(any());
        assertThat(fact.lines().get(2).inventoryValueMinor())
                .as("an unpriced line is valued at the row's cost, accrual 0")
                .isEqualTo(15_000L);
    }

    /** Every SKU the receipt resolves takes {@code method}; resolved once per receipt, never per line. */
    @SuppressWarnings("unchecked")
    private void stubMethod(CostingMethod method) {
        when(methods.resolveAll(any()))
                .thenAnswer(invocation -> ((java.util.Set<String>) invocation.getArgument(0))
                        .stream().collect(java.util.stream.Collectors.toMap(sku -> sku, sku -> method)));
    }

    private GoodsReceiptRecordedV1 publish(String currency, GoodsReceiptFactPublisher.GoodsReceiptLineFact... lines) {
        GoodsReceiptEntity receipt = GoodsReceiptEntity.builder()
                .receiptId(RECEIPT_ID)
                .receiptNumber("GR-1")
                .purchaseOrderId(ORDER_ID)
                .createdBy("receiver")
                .build();
        publisher.publish(receipt, currency, List.of(lines));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEnvelope<GoodsReceiptRecordedV1>> envelope =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("inventory.events.v1"), envelope.capture());
        assertThat(envelope.getValue().eventType()).isEqualTo(GoodsReceiptRecordedV1.EVENT_TYPE);
        assertThat(envelope.getValue().aggregateId()).isEqualTo(ORDER_ID);
        return envelope.getValue().payload();
    }

    private static GoodsReceiptFactPublisher.GoodsReceiptLineFact fact(
            UUID receiptLineId, String quantity, long accruedMinor, InventoryLedgerEntry row) {
        return new GoodsReceiptFactPublisher.GoodsReceiptLineFact(
                PO_LINE, PRODUCT.toString(), new BigDecimal(quantity), accruedMinor, receiptLineId, PRODUCT, row);
    }

    private static InventoryLedgerEntry row(UUID ledgerEntryId, String unitCost) {
        return InventoryLedgerEntry.builder()
                .ledgerEntryId(ledgerEntryId)
                .stockItemId(PRODUCT.toString())
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .unitCost(unitCost == null ? null : new BigDecimal(unitCost))
                .build();
    }
}
