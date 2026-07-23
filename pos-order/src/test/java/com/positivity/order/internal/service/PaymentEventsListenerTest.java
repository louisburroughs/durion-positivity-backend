package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.OrderStatusHistoryRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the settlement handshake consumer (parity story C3, #1072): ledger writes,
 * amountPaid/balanceDue maintenance, cent-exact completion (spec R2.2), replay dedup, reversal
 * handling, and the over-settlement integrity alert (spec R4.4).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PaymentEventsListener — parity story C3")
class PaymentEventsListenerTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INTENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private OrderPaymentRecordRepository paymentRecordRepository;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private OrderDomainEventPublisher domainEventPublisher;

    private final List<OrderPaymentRecord> ledger = new ArrayList<>();

    private PaymentEventsListener listener;

    private SalesOrder order;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);
        listener = new PaymentEventsListener(
                clock,
                new ObjectMapper(),
                processedEventRepository,
                salesOrderRepository,
                paymentRecordRepository,
                new OrderStateMachine(orderStatusHistoryRepository, clock),
                domainEventPublisher);

        order = SalesOrder.builder()
                .orderId(ORDER_ID)
                .clerkId("clerk-1")
                .terminalId("terminal-1")
                .status(SalesOrderStatus.PENDING_PAYMENT)
                .subtotal(money("100.00"))
                .grandTotal(money("100.00"))
                .invoiceId(INVOICE_ID)
                .balanceDue(money("100.00"))
                .createdBy("clerk-1")
                .updatedBy("clerk-1")
                .build();

        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ledger.clear();
        when(paymentRecordRepository.save(any())).thenAnswer(inv -> {
            ledger.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(paymentRecordRepository.findByOrderId(ORDER_ID)).thenAnswer(inv -> List.copyOf(ledger));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static String settledEnvelope(String eventId, String amount) {
        return """
                {"eventId":"%s","eventType":"payment.payment.settled","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,"occurredAtUtc":"2026-07-23T11:59:00Z",
                 "sourceService":"pos-invoice",
                 "payload":{"paymentIntentId":"%s","invoiceId":"%s","orderId":"%s",
                   "methodType":"CARD","amount":%s,"currencyCode":"USD",
                   "gatewayReference":"ref-1","settledAt":"2026-07-23T11:59:00Z"}}
                """.formatted(eventId, INTENT_ID, INTENT_ID, INVOICE_ID, ORDER_ID, amount);
    }

    private static String reversedEnvelope(String eventId, String type, String amount) {
        return """
                {"eventId":"%s","eventType":"payment.payment.reversed","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,"occurredAtUtc":"2026-07-23T11:59:30Z",
                 "sourceService":"pos-invoice",
                 "payload":{"paymentIntentId":"%s","invoiceId":"%s","orderId":"%s",
                   "reversalType":"%s","amount":%s,"currencyCode":"USD",
                   "reversedAt":"2026-07-23T11:59:30Z"}}
                """.formatted(eventId, INTENT_ID, INTENT_ID, INVOICE_ID, ORDER_ID, type, amount);
    }

    @Test
    @DisplayName("PEL-001: settle-in-full → COMPLETED, order.order.completed emitted, balanceDue 0")
    void settledInFull_completesOrder() {
        listener.onPaymentEvent(settledEnvelope("evt-1", "100.00"));

        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.COMPLETED);
        assertThat(order.getAmountPaid()).isEqualByComparingTo("100.00");
        assertThat(order.getBalanceDue()).isEqualByComparingTo("0");
        verify(domainEventPublisher).publishOrderCompleted(eq(order), anyList());
        verify(domainEventPublisher, never()).publishPaymentIntegrityAlert(any());
        verify(processedEventRepository).save(any());
        assertThat(ledger).hasSize(1);
        assertThat(ledger.getFirst().getRecordType()).isEqualTo(OrderPaymentRecord.RecordType.SETTLED);
    }

    @Test
    @DisplayName("PEL-002: partial settlement stays PENDING_PAYMENT with correct balanceDue")
    void partialSettlement_staysPending() {
        listener.onPaymentEvent(settledEnvelope("evt-1", "40.00"));

        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.PENDING_PAYMENT);
        assertThat(order.getAmountPaid()).isEqualByComparingTo("40.00");
        assertThat(order.getBalanceDue()).isEqualByComparingTo("60.00");
        verify(domainEventPublisher, never()).publishOrderCompleted(any(), anyList());
    }

    @Test
    @DisplayName("PEL-003: one cent short does NOT complete (spec R2.2, cent-exact)")
    void oneCentShort_doesNotComplete() {
        listener.onPaymentEvent(settledEnvelope("evt-1", "99.99"));

        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.PENDING_PAYMENT);
        assertThat(order.getBalanceDue()).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("PEL-004: replayed eventId no-ops")
    void replayedEvent_noOps() {
        when(processedEventRepository.existsById("evt-1")).thenReturn(true);

        listener.onPaymentEvent(settledEnvelope("evt-1", "100.00"));

        assertThat(ledger).isEmpty();
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("PEL-005: REFUND reversal reduces amountPaid")
    void refundReversal_reducesAmountPaid() {
        listener.onPaymentEvent(settledEnvelope("evt-1", "40.00"));
        listener.onPaymentEvent(reversedEnvelope("evt-2", "REFUND", "15.00"));

        assertThat(order.getAmountPaid()).isEqualByComparingTo("25.00");
        assertThat(order.getBalanceDue()).isEqualByComparingTo("75.00");
        assertThat(ledger).hasSize(2);
    }

    @Test
    @DisplayName("PEL-006: VOID reversal leaves the settled ledger untouched")
    void voidReversal_ignored() {
        listener.onPaymentEvent(reversedEnvelope("evt-1", "VOID", "40.00"));

        assertThat(ledger).isEmpty();
        assertThat(order.getAmountPaid()).isEqualByComparingTo("0");
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("PEL-007: over-settlement completes AND raises the integrity alert (spec R4.4)")
    void overSettlement_alertsAndCompletes() {
        listener.onPaymentEvent(settledEnvelope("evt-1", "120.00"));

        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.COMPLETED);
        assertThat(order.getAmountPaid()).isEqualByComparingTo("120.00");
        assertThat(order.getBalanceDue()).isEqualByComparingTo("0");
        verify(domainEventPublisher).publishPaymentIntegrityAlert(order);
        verify(domainEventPublisher).publishOrderCompleted(eq(order), anyList());
    }

    @Test
    @DisplayName("PEL-008: event with no matching order is recorded processed, no ledger write")
    void unknownOrder_recordedProcessed() {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());
        when(salesOrderRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.empty());

        listener.onPaymentEvent(settledEnvelope("evt-1", "100.00"));

        assertThat(ledger).isEmpty();
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("PEL-009: order resolved via invoiceId when payload lacks orderId")
    void resolvesOrderByInvoiceId() {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());
        when(salesOrderRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(order));

        listener.onPaymentEvent(settledEnvelope("evt-1", "100.00"));

        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("PEL-010: irrelevant event types are ignored without dedup writes")
    void irrelevantEventType_ignored() {
        listener.onPaymentEvent("{\"eventId\":\"evt-x\",\"eventType\":\"payment.settlement.reported\",\"payload\":{}}");

        assertThat(ledger).isEmpty();
        verify(processedEventRepository, never()).save(any());
    }
}
