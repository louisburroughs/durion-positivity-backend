package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * #2634: a {@code goodsreceipt.recorded} fact moves the order's status only while the order is being
 * received ({@code APPROVED}, {@code PARTIALLY_RECEIVED}, {@code FULLY_RECEIVED}). A receipt for a
 * {@code CANCELLED} or {@code CLOSED} order still records its quantities and value, is marked
 * processed and republishes the order, but the status is kept, so the order never re-enters
 * incoming supply.
 */
@DisplayName("goodsreceipt.recorded consumer — status guard (#2634)")
class GoodsReceiptStatusGuardTest {

    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
    private static final UUID ORDER = UUID.fromString("019a0000-0000-7000-8000-000000002634");
    private static final UUID LINE_1 = UUID.fromString("019a0000-0000-7000-8000-0000000026a1");
    private static final UUID LINE_2 = UUID.fromString("019a0000-0000-7000-8000-0000000026a2");
    private static final UUID RECEIPT = UUID.fromString("019a0000-0000-7000-8000-0000000026e1");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProcessedEventRepository processedEvents;
    private PurchaseOrderRepository orders;
    private PurchaseOrderFactPublisher publisher;
    private InventoryEventsListener listener;
    private Logger listenerLogger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        processedEvents = mock(ProcessedEventRepository.class);
        orders = mock(PurchaseOrderRepository.class);
        publisher = mock(PurchaseOrderFactPublisher.class);
        when(orders.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        listener = new InventoryEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                objectMapper,
                processedEvents,
                orders,
                publisher,
                mock(ExtInventoryAvailabilityRepository.class),
                mock(SalesOrderLineRepository.class),
                mock(PlatformTransactionManager.class));

        listenerLogger = (Logger) LoggerFactory.getLogger(InventoryEventsListener.class);
        logs = new ListAppender<>();
        logs.start();
        listenerLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        listenerLogger.detachAppender(logs);
    }

    /** Every status outside the receiving lifecycle, so a status added later is covered too. */
    @ParameterizedTest(name = "{0}: status kept, quantities and balance recorded, fact republished")
    @EnumSource(
            value = PurchaseOrderStatus.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"APPROVED", "PARTIALLY_RECEIVED", "FULLY_RECEIVED"})
    @DisplayName("a receipt for an order outside the receiving lifecycle (cancelled, closed, draft) keeps its status")
    void receiptOutsideReceivingLifecycle_keepsStatus(PurchaseOrderStatus status) {
        PurchaseOrderEntity order = order(status);
        when(orders.findByIdForUpdate(ORDER)).thenReturn(Optional.of(order));

        listener.onInventoryEvent(receipt(RECEIPT, LINE_1, "4", 40_000L));

        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(line(order, LINE_1).getOpenQuantityDecimal()).isEqualByComparingTo("6");
        assertThat(order.getOpenBalanceMinor()).isEqualTo(160_000L);
        assertThat(order.getVersionNumber()).isEqualTo(4);
        verify(processedEvents).save(any(ProcessedEvent.class));

        PurchaseOrderUpdatedV1 fact = publishedFact();
        assertThat(fact.status()).isEqualTo(status.name());
        assertThat(fact.countsAsOpenSupply()).isFalse();
        assertThat(fact.openBalanceMinor()).isEqualTo(160_000L);
        assertThat(fact.lines())
                .filteredOn(line -> LINE_1.equals(line.lineId()))
                .singleElement()
                .satisfies(line -> assertThat(line.openQuantity()).isEqualByComparingTo("6"));

        assertThat(logs.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains(RECEIPT.toString(), ORDER.toString(), status.name(), "status kept"));
    }

    @ParameterizedTest(name = "{0}, line 2 already settled: {1} -> {2}")
    @CsvSource({
        "APPROVED, false, PARTIALLY_RECEIVED",
        "PARTIALLY_RECEIVED, false, PARTIALLY_RECEIVED",
        "APPROVED, true, FULLY_RECEIVED",
        "PARTIALLY_RECEIVED, true, FULLY_RECEIVED",
    })
    @DisplayName("an order being received moves along the receiving lifecycle as before")
    void receiptForReceivingOrder_recomputesStatus(
            PurchaseOrderStatus status, boolean lineTwoSettled, PurchaseOrderStatus expected) {
        PurchaseOrderEntity order = order(status);
        if (lineTwoSettled) {
            // Line 1 receiving all 10 is then the last open quantity on the order.
            line(order, LINE_2).setOpenQuantityDecimal(BigDecimal.ZERO);
        }
        when(orders.findByIdForUpdate(ORDER)).thenReturn(Optional.of(order));

        listener.onInventoryEvent(receipt(RECEIPT, LINE_1, lineTwoSettled ? "10" : "4", 40_000L));

        assertThat(order.getStatus()).isEqualTo(expected);
        assertThat(publishedFact().status()).isEqualTo(expected.name());
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    @DisplayName("an over-receipt on a fully received order keeps it FULLY_RECEIVED, without a warning")
    void fullyReceivedOrder_overReceipt_staysFullyReceived() {
        PurchaseOrderEntity order = order(PurchaseOrderStatus.FULLY_RECEIVED);
        order.getLines().forEach(line -> line.setOpenQuantityDecimal(BigDecimal.ZERO));
        order.setOpenBalanceMinor(0L);
        when(orders.findByIdForUpdate(ORDER)).thenReturn(Optional.of(order));

        listener.onInventoryEvent(receipt(UUID.randomUUID(), LINE_1, "1", 10_000L));

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.FULLY_RECEIVED);
        assertThat(line(order, LINE_1).getOpenQuantityDecimal()).isEqualByComparingTo("0");
        assertThat(publishedFact().status()).isEqualTo("FULLY_RECEIVED");
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    @DisplayName("receive, cancel, then a late receipt: the order stays CANCELLED and out of open supply")
    void receiveThenCancelThenLateReceipt_staysCancelled() {
        PurchaseOrderEntity order = order(PurchaseOrderStatus.APPROVED);
        when(orders.findByIdForUpdate(ORDER)).thenReturn(Optional.of(order));

        listener.onInventoryEvent(receipt(UUID.randomUUID(), LINE_1, "4", 40_000L));
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);

        // What cancelPurchaseOrder does: the status changes and every open quantity stays in place.
        order.setStatus(PurchaseOrderStatus.CANCELLED);

        listener.onInventoryEvent(receipt(UUID.randomUUID(), LINE_2, "3", 30_000L));

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(line(order, LINE_1).getOpenQuantityDecimal()).isEqualByComparingTo("6");
        assertThat(line(order, LINE_2).getOpenQuantityDecimal()).isEqualByComparingTo("7");
        assertThat(order.getOpenBalanceMinor()).isEqualTo(130_000L);

        ArgumentCaptor<PurchaseOrderEntity> published = ArgumentCaptor.forClass(PurchaseOrderEntity.class);
        verify(publisher, times(2)).publish(published.capture(), anyList());
        PurchaseOrderUpdatedV1 lastFact = PurchaseOrderFactPublisher.toFact(
                published.getValue(), published.getValue().getLines(), NOW);
        assertThat(lastFact.status()).isEqualTo("CANCELLED");
        assertThat(PurchaseOrderUpdatedV1.OPEN_SUPPLY_STATUSES).doesNotContain(lastFact.status());
    }

    private PurchaseOrderUpdatedV1 publishedFact() {
        ArgumentCaptor<PurchaseOrderEntity> published = ArgumentCaptor.forClass(PurchaseOrderEntity.class);
        verify(publisher).publish(published.capture(), anyList());
        return PurchaseOrderFactPublisher.toFact(
                published.getValue(), published.getValue().getLines(), NOW);
    }

    private static PurchaseOrderLineEntity line(PurchaseOrderEntity order, UUID lineId) {
        return order.getLines().stream()
                .filter(line -> lineId.equals(line.getLineId()))
                .findFirst()
                .orElseThrow();
    }

    /** Two lines of 10 at 100.00 each, nothing received yet, version 3. */
    private static PurchaseOrderEntity order(PurchaseOrderStatus status) {
        List<PurchaseOrderLineEntity> lines = new ArrayList<>();
        int lineNumber = 1;
        for (UUID lineId : List.of(LINE_1, LINE_2)) {
            lines.add(PurchaseOrderLineEntity.builder()
                    .lineId(lineId)
                    .lineNumber(lineNumber++)
                    .skuId(UUID.randomUUID())
                    .unitCostMinor(10_000L)
                    .quantityDecimal(new BigDecimal("10"))
                    .openQuantityDecimal(new BigDecimal("10"))
                    .build());
        }
        return PurchaseOrderEntity.builder()
                .purchaseOrderId(ORDER)
                .poNumber("PO-2634")
                .vendorId(UUID.fromString("019a0000-0000-7000-8000-0000000026f0"))
                .status(status)
                .currency("USD")
                .grandTotalMinor(200_000L)
                .openBalanceMinor(200_000L)
                .versionNumber(3)
                .lines(lines)
                .build();
    }

    private String receipt(UUID receiptId, UUID poLineId, String quantity, long accruedMinor) {
        String payload = """
                {"receiptId":"%s","receiptNumber":"GR-2634","purchaseOrderId":"%s","locationId":null,
                 "totalAccruedAmountMinor":%d,"occurredAt":"%s",
                 "lines":[{"poLineId":"%s","sku":"SKU","quantityReceived":%s,"accruedAmountMinor":%d}]}
                """.formatted(receiptId, ORDER, accruedMinor, NOW, poLineId, quantity, accruedMinor);
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"%s","sourceService":"pos-inventory","payload":%s}
                """.formatted(UUID.randomUUID(), GoodsReceiptRecordedV1.EVENT_TYPE, ORDER, NOW, payload);
    }
}
