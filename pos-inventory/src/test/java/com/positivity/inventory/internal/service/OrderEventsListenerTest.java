package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.InsufficientStockException;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the counter-sale consumption consumer (order parity story H2, #1079; order-spec
 * R8.2, R7.5): GOODS_ISSUE per fulfillable line, WORKORDER-line defensive exclusion, replay
 * dedup, and failure isolation (a rejected line never affects the others or the sale).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderEventsListener — parity story H2")
class OrderEventsListenerTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private CounterSaleIssuePoster counterSaleIssuePoster;

    @Mock
    private ObjectProvider<com.positivity.inventory.internal.config.OutboxEventWriter> outboxEventWriter;

    private final List<InventoryLedgerEntry> posted = new ArrayList<>();

    private OrderEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new OrderEventsListener(
                Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC),
                new ObjectMapper(),
                processedEventRepository,
                counterSaleIssuePoster,
                outboxEventWriter);
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(outboxEventWriter.getIfAvailable()).thenReturn(null);
        posted.clear();
        org.mockito.Mockito.doAnswer(inv -> {
                    posted.add(inv.getArgument(0));
                    return null;
                })
                .when(counterSaleIssuePoster)
                .post(any());
    }

    private static String completedEnvelope(String eventId, String linesJson) {
        return """
                {"eventId":"%s","eventType":"order.order.completed","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":1,"occurredAtUtc":"2026-07-23T11:59:00Z",
                 "sourceService":"pos-order",
                 "payload":{"orderId":"%s","orderNumber":"SO-1","locationId":"%s",
                   "clerkId":"clerk-1","terminalId":"t-1","currencyCode":"USD",
                   "subtotal":100.0,"discountTotal":0,"taxTotal":0,"grandTotal":100.0,
                   "lines":[%s],"tenders":[],"completedAt":"2026-07-23T11:59:00Z"}}
                """.formatted(eventId, ORDER_ID, ORDER_ID, LOCATION_ID, linesJson);
    }

    private static String line(String sku, int qty, boolean fulfillable, String sourceType) {
        return """
                {"orderLineId":"%s","sku":"%s","description":"d","quantity":%d,
                 "unitPrice":10.0,"discountAmount":0,"lineSubtotal":10.0,"taxAmount":0,
                 "lineTotal":10.0,"priceSource":"PRICING_SERVICE","sourceType":%s,
                 "serialNumbers":[],"fulfillable":%s}""".formatted(
                UUID.randomUUID(), sku, qty, sourceType == null ? "null" : "\"" + sourceType + "\"", fulfillable);
    }

    @Test
    @DisplayName("HIL-001: fulfillable counter line posts a negative GOODS_ISSUE at the order's location")
    void fulfillableLine_postsIssue() {
        listener.onOrderEvent(completedEnvelope("evt-1", line("SKU-1", 2, true, null)));

        assertThat(posted).hasSize(1);
        InventoryLedgerEntry entry = posted.getFirst();
        assertThat(entry.getStockItemId()).isEqualTo("SKU-1");
        assertThat(entry.getEventType()).isEqualTo(InventoryLedgerEventType.GOODS_ISSUE);
        assertThat(entry.getChangeInQuantity()).isEqualByComparingTo("-2");
        assertThat(entry.getLocationId()).isEqualTo(LOCATION_ID);
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("HIL-002: non-fulfillable and WORKORDER-sourced lines move nothing (spec R7.5)")
    void workorderAndNonFulfillableLines_skipped() {
        String lines = line("SKU-1", 1, false, null) + "," + line("SKU-2", 1, true, "WORKORDER");

        listener.onOrderEvent(completedEnvelope("evt-1", lines));

        assertThat(posted).isEmpty();
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("HIL-003: replayed eventId no-ops")
    void replayedEvent_noOps() {
        when(processedEventRepository.existsById("evt-1")).thenReturn(true);

        listener.onOrderEvent(completedEnvelope("evt-1", line("SKU-1", 1, true, null)));

        assertThat(posted).isEmpty();
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("HIL-004: a failing line alerts and never blocks the remaining lines")
    void failingLine_isolated() {
        org.mockito.Mockito.doThrow(new InsufficientStockException("no stock", UUID.randomUUID()))
                .doAnswer(inv -> {
                    posted.add(inv.getArgument(0));
                    return null;
                })
                .when(counterSaleIssuePoster)
                .post(any());

        String lines = line("SKU-OUT", 5, true, null) + "," + line("SKU-OK", 1, true, null);
        listener.onOrderEvent(completedEnvelope("evt-1", lines));

        assertThat(posted).hasSize(1);
        assertThat(posted.getFirst().getStockItemId()).isEqualTo("SKU-OK");
        verify(processedEventRepository).save(any());
        ArgumentCaptor<InventoryLedgerEntry> unused = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(counterSaleIssuePoster, org.mockito.Mockito.times(2)).post(unused.capture());
    }

    @Test
    @DisplayName("HIL-005: unrelated order event types are recorded processed without postings")
    void unrelatedEventType_recorded() {
        listener.onOrderEvent("{\"eventId\":\"evt-x\",\"eventType\":\"order.order.cancelled\",\"payload\":{}}");

        assertThat(posted).isEmpty();
        verify(processedEventRepository).save(any());
    }

    // ── F2 return restock (#1087) ────────────────────────────────────────────

    private static final UUID RETURN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    private static String returnedEnvelope(String eventId, String linesJson) {
        return """
                {"eventId":"%s","eventType":"order.order.returned","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":1,"sourceService":"pos-order",
                 "payload":{"returnOrderId":"%s","originalOrderId":"%s","orderNumber":"SO-1",
                   "locationId":"%s","customerId":null,"refundMethod":"ORIGINAL_TENDER",
                   "totalRefund":10.0,"lines":[%s],"returnedAt":"2026-07-24T12:00:00Z"}}
                """.formatted(eventId, RETURN_ID, RETURN_ID, ORDER_ID, LOCATION_ID, linesJson);
    }

    private static String returnLine(String sku, int qty, String condition) {
        return """
                {"originalOrderLineId":"%s","sku":"%s","quantity":%d,"condition":"%s",
                 "amount":10.0,"serialNumbers":[]}""".formatted(UUID.randomUUID(), sku, qty, condition);
    }

    @Test
    @DisplayName("HIL-006: a RESTOCK return line posts a positive RETURN_TO_STOCK movement")
    void restockLine_postsReturnToStock() {
        listener.onOrderEvent(returnedEnvelope("evt-r1", returnLine("SKU-1", 2, "RESTOCK")));

        assertThat(posted).hasSize(1);
        InventoryLedgerEntry entry = posted.getFirst();
        assertThat(entry.getEventType()).isEqualTo(InventoryLedgerEventType.RETURN_TO_STOCK);
        assertThat(entry.getStockItemId()).isEqualTo("SKU-1");
        assertThat(entry.getChangeInQuantity()).isEqualByComparingTo("2");
        assertThat(entry.getLocationId()).isEqualTo(LOCATION_ID);
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("HIL-007: SCRAP and WARRANTY return lines move no inventory")
    void scrapAndWarrantyLines_skipped() {
        String lines = returnLine("SKU-1", 1, "SCRAP") + "," + returnLine("SKU-2", 1, "WARRANTY");

        listener.onOrderEvent(returnedEnvelope("evt-r2", lines));

        assertThat(posted).isEmpty();
        verify(processedEventRepository).save(any());
    }
}
