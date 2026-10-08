package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.repository.ExtInventoryAvailabilityRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * pos-order's {@code goodsreceipt.recorded} consumer is unaffected by the receipt fact's additive v1 fields (CAP:550
 * S41 #2602): a fact carrying the currency and the per-line cost basis applies exactly as one without them, and pos-order
 * reads none of the new fields.
 */
@DisplayName("goodsreceipt.recorded consumer — additive cost-basis fields (S41 #2602)")
class GoodsReceiptRecordedConsumerTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final UUID ORDER = UUID.fromString("019a0000-0000-7000-8000-0000000000f1");
    private static final UUID PO_LINE = UUID.fromString("019a0000-0000-7000-8000-0000000000f2");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProcessedEventRepository processedEvents;
    private PurchaseOrderRepository orders;
    private InventoryEventsListener listener;

    @BeforeEach
    void setUp() {
        processedEvents = mock(ProcessedEventRepository.class);
        orders = mock(PurchaseOrderRepository.class);
        when(orders.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        listener = new InventoryEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                objectMapper,
                processedEvents,
                orders,
                mock(PurchaseOrderFactPublisher.class),
                mock(ExtInventoryAvailabilityRepository.class),
                mock(SalesOrderLineRepository.class),
                mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("a fact with currencyCode and the per-line cost basis settles the line and the balance as before")
    void factWithTheNewFieldsApplies() {
        PurchaseOrderEntity order = order();
        when(orders.findById(ORDER)).thenReturn(Optional.of(order));

        listener.onInventoryEvent(envelope("""
                {"receiptId":"%s","receiptNumber":"GR-1","purchaseOrderId":"%s","locationId":null,
                 "totalAccruedAmountMinor":40000,"occurredAt":"%s","currencyCode":"USD",
                 "lines":[{"poLineId":"%s","sku":"SKU-1","quantityReceived":4,"accruedAmountMinor":40000,
                   "receiptLineId":"%s","productId":"%s","inventoryValueMinor":38000,"costSource":"STANDARD",
                   "ledgerEntryId":"%s"}]}
                """.formatted(
                UUID.randomUUID(), ORDER, NOW, PO_LINE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));

        assertSettled(order);
    }

    @Test
    @DisplayName("a fact published before the fields existed still applies")
    void factWithoutTheNewFieldsApplies() {
        PurchaseOrderEntity order = order();
        when(orders.findById(ORDER)).thenReturn(Optional.of(order));

        listener.onInventoryEvent(envelope("""
                {"receiptId":"%s","receiptNumber":"GR-1","purchaseOrderId":"%s","locationId":null,
                 "totalAccruedAmountMinor":40000,"occurredAt":"%s",
                 "lines":[{"poLineId":"%s","sku":"SKU-1","quantityReceived":4,"accruedAmountMinor":40000}]}
                """.formatted(UUID.randomUUID(), ORDER, NOW, PO_LINE)));

        assertSettled(order);
    }

    private void assertSettled(PurchaseOrderEntity order) {
        verify(orders).save(order);
        assertThat(order.getLines().getFirst().getOpenQuantityDecimal()).isEqualByComparingTo("6");
        assertThat(order.getOpenBalanceMinor()).isEqualTo(60_000L);
    }

    private static PurchaseOrderEntity order() {
        PurchaseOrderLineEntity line = PurchaseOrderLineEntity.builder()
                .lineId(PO_LINE)
                .quantityDecimal(new BigDecimal("10"))
                .openQuantityDecimal(new BigDecimal("10"))
                .build();
        return PurchaseOrderEntity.builder()
                .purchaseOrderId(ORDER)
                .openBalanceMinor(100_000L)
                .lines(new ArrayList<>(List.of(line)))
                .build();
    }

    private static String envelope(String payload) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"%s","sourceService":"pos-inventory","payload":%s}
                """.formatted(UUID.randomUUID(), GoodsReceiptRecordedV1.EVENT_TYPE, ORDER, NOW, payload);
    }
}
