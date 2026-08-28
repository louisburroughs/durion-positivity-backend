package com.positivity.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.client.CancelWorkorderCommand;
import com.positivity.order.internal.client.InvoicingPort;
import com.positivity.order.internal.client.PaymentReversalResult;
import com.positivity.order.internal.client.ReversePaymentCommand;
import com.positivity.order.internal.client.WorkexecPort;
import com.positivity.order.internal.client.WorkorderCancelResult;
import com.positivity.order.internal.client.WorkorderStatusResult;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.OrderCancellationServiceImpl;
import com.positivity.order.internal.service.model.CancelOrderCommand;
import com.positivity.order.internal.service.model.CancellationResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link OrderCancellationServiceImpl} covering the POS cancellation saga: state
 * gating, Workexec gating, ledger-driven payment reversal (spec R4.6, story C3), idempotency, and
 * the retry path.
 *
 * <p>Issues: #19, #1072.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderCancellationServiceImpl Unit Tests")
class OrderCancellationServiceImplTest {

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private OrderPaymentRecordRepository paymentRecordRepository;

    @Mock
    private WorkexecPort workexecPort;

    @Mock
    private InvoicingPort invoicingPort;

    @Mock
    private com.positivity.order.internal.repository.OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private com.positivity.order.internal.config.OrderDomainEventPublisher orderDomainEventPublisher;

    private OrderCancellationServiceImpl orderCancellationService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        orderCancellationService = new OrderCancellationServiceImpl(
                salesOrderRepository,
                paymentRecordRepository,
                workexecPort,
                invoicingPort,
                new com.positivity.order.internal.service.OrderStateMachine(
                        orderStatusHistoryRepository, java.time.Clock.systemUTC()),
                orderDomainEventPublisher);
        when(paymentRecordRepository.findByOrderId(any())).thenReturn(List.of());
    }

    // ── shared fixtures ───────────────────────────────────────────────────────

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID INTENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private SalesOrder draftOrder() {
        return SalesOrder.builder()
                .orderId(ORDER_ID)
                .clerkId("clerk-1")
                .terminalId("terminal-1")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.valueOf(100.00))
                .createdBy("clerk-1")
                .updatedBy("clerk-1")
                .build();
    }

    private SalesOrder quotedOrderWithWorkorder() {
        SalesOrder order = draftOrder();
        order.setStatus(SalesOrderStatus.QUOTED);
        order.setWorkOrderId(WORKORDER_ID);
        return order;
    }

    private OrderPaymentRecord settledRecord(BigDecimal amount) {
        return OrderPaymentRecord.builder()
                .paymentRecordId(UUID.randomUUID())
                .orderId(ORDER_ID)
                .recordType(OrderPaymentRecord.RecordType.SETTLED)
                .paymentIntentId(INTENT_ID)
                .methodType("CARD")
                .amount(amount)
                .occurredAt(Instant.now())
                .createdAt(Instant.now())
                .build();
    }

    private CancelOrderCommand cancelCommand(UUID workOrderId) {
        return new CancelOrderCommand("Customer request", workOrderId, "idem-key-1");
    }

    // ── happy paths ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("CC-001: DRAFT order, no workorder, no settled payments → CANCELLED, no port calls")
    void cancelOrder_happyPath_noWorkorder_noPayments() {
        SalesOrder order = draftOrder();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancellationResult result = orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null));

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        verify(workexecPort, never()).checkWorkorderStatus(any());
        verify(invoicingPort, never()).reversePayment(any(), any(), any());
        verify(orderDomainEventPublisher).publishOrderCancelled(order);
    }

    @Test
    @DisplayName("CC-002: QUOTED order with workorder → Workexec checked and cancelled, result CANCELLED")
    void cancelOrder_happyPath_withWorkorder() {
        SalesOrder order = quotedOrderWithWorkorder();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workexecPort.checkWorkorderStatus(WORKORDER_ID)).thenReturn(new WorkorderStatusResult("OPEN", true, null));
        when(workexecPort.cancelWorkorder(eq(WORKORDER_ID), any(CancelWorkorderCommand.class)))
                .thenReturn(new WorkorderCancelResult(true, "Cancelled"));

        CancellationResult result = orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(WORKORDER_ID));

        assertThat(result.status()).isEqualTo("CANCELLED");
        verify(workexecPort).checkWorkorderStatus(WORKORDER_ID);
        verify(workexecPort).cancelWorkorder(eq(WORKORDER_ID), any(CancelWorkorderCommand.class));
        verify(invoicingPort, never()).reversePayment(any(), any(), any());
    }

    @Test
    @DisplayName("CC-003: settled payment on the ledger → refund issued for the net amount, CANCELLED")
    void cancelOrder_withSettledPayment_reversesLedgerEntry() {
        SalesOrder order = draftOrder();
        order.setInvoiceId(INVOICE_ID);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRecordRepository.findByOrderId(ORDER_ID))
                .thenReturn(List.of(settledRecord(new BigDecimal("80.00"))));
        when(invoicingPort.reversePayment(eq(INVOICE_ID), eq(INTENT_ID), any(ReversePaymentCommand.class)))
                .thenReturn(new PaymentReversalResult(true, "REFUND", "Reversed"));

        CancellationResult result = orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null));

        assertThat(result.status()).isEqualTo("CANCELLED");
        org.mockito.ArgumentCaptor<ReversePaymentCommand> captor =
                org.mockito.ArgumentCaptor.forClass(ReversePaymentCommand.class);
        verify(invoicingPort).reversePayment(eq(INVOICE_ID), eq(INTENT_ID), captor.capture());
        assertThat(captor.getValue().amount()).isEqualByComparingTo("80.00");
        assertThat(captor.getValue().type()).isEqualTo("REFUND");
    }

    @Test
    @DisplayName("CC-003b: already-reversed ledger entries net to zero → no reversal call")
    void cancelOrder_netZeroLedger_skipsReversal() {
        SalesOrder order = draftOrder();
        order.setInvoiceId(INVOICE_ID);
        OrderPaymentRecord reversal = settledRecord(new BigDecimal("80.00"));
        reversal.setRecordType(OrderPaymentRecord.RecordType.REVERSED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRecordRepository.findByOrderId(ORDER_ID))
                .thenReturn(List.of(settledRecord(new BigDecimal("80.00")), reversal));

        CancellationResult result = orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null));

        assertThat(result.status()).isEqualTo("CANCELLED");
        verify(invoicingPort, never()).reversePayment(any(), any(), any());
    }

    // ── Workexec failure paths ────────────────────────────────────────────────

    @Test
    @DisplayName("CC-004: Workexec cancellable=false → IllegalStateException, order CANCEL_FAILED_WORKEXEC")
    void cancelOrder_workexec_notCancellable_throwsIllegalState() {
        SalesOrder order = quotedOrderWithWorkorder();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workexecPort.checkWorkorderStatus(WORKORDER_ID))
                .thenReturn(new WorkorderStatusResult("IN_PROGRESS", false, "Work already started"));

        assertThatThrownBy(() -> orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(WORKORDER_ID)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.CANCEL_FAILED_WORKEXEC);
    }

    @Test
    @DisplayName("CC-005: Workexec cancelWorkorder success=false → IllegalStateException, CANCEL_FAILED_WORKEXEC")
    void cancelOrder_workexec_cancelFails_statusSetToFailWorkexec() {
        SalesOrder order = quotedOrderWithWorkorder();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workexecPort.checkWorkorderStatus(WORKORDER_ID)).thenReturn(new WorkorderStatusResult("OPEN", true, null));
        when(workexecPort.cancelWorkorder(eq(WORKORDER_ID), any(CancelWorkorderCommand.class)))
                .thenReturn(new WorkorderCancelResult(false, "Failed to cancel"));

        assertThatThrownBy(() -> orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(WORKORDER_ID)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.CANCEL_FAILED_WORKEXEC);
    }

    // ── Billing failure path ──────────────────────────────────────────────────

    @Test
    @DisplayName("CC-006: reversal fails → IllegalStateException, CANCEL_FAILED_BILLING")
    void cancelOrder_billing_reversalFails_statusSetToFailBilling() {
        SalesOrder order = draftOrder();
        order.setInvoiceId(INVOICE_ID);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRecordRepository.findByOrderId(ORDER_ID))
                .thenReturn(List.of(settledRecord(new BigDecimal("80.00"))));
        when(invoicingPort.reversePayment(eq(INVOICE_ID), eq(INTENT_ID), any(ReversePaymentCommand.class)))
                .thenReturn(new PaymentReversalResult(false, null, "Billing system unavailable"));

        assertThatThrownBy(() -> orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.CANCEL_FAILED_BILLING);
    }

    @Test
    @DisplayName("CC-006b: settled payments but no invoice reference → CANCEL_FAILED_BILLING")
    void cancelOrder_settledPaymentsWithoutInvoice_failsBilling() {
        SalesOrder order = draftOrder();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRecordRepository.findByOrderId(ORDER_ID))
                .thenReturn(List.of(settledRecord(new BigDecimal("80.00"))));

        assertThatThrownBy(() -> orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.CANCEL_FAILED_BILLING);
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("CC-007: Already CANCELLED + matching idempotencyKey → existing result, no ports called")
    void cancelOrder_idempotency_alreadyCancelled_returnsExisting() {
        SalesOrder order = draftOrder();
        order.setStatus(SalesOrderStatus.CANCELLED);
        order.setCancellationIdempotencyKey("idem-key-1");
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CancellationResult result = orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null));

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.cancellationIdempotencyKey()).isEqualTo("idem-key-1");
        verify(workexecPort, never()).checkWorkorderStatus(any());
        verify(invoicingPort, never()).reversePayment(any(), any(), any());
    }

    // ── Invalid state / not found ─────────────────────────────────────────────

    @Test
    @DisplayName("CC-008: COMPLETED order → IllegalStateException — order cannot be cancelled")
    void cancelOrder_invalidState_completed_throwsIllegalState() {
        SalesOrder order = draftOrder();
        order.setStatus(SalesOrderStatus.COMPLETED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancel");
    }

    @Test
    @DisplayName("CC-009: Order not found → SalesOrderNotFoundException")
    void cancelOrder_orderNotFound_throwsSalesOrderNotFoundException() {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null)))
                .isInstanceOf(SalesOrderNotFoundException.class);
    }

    // ── Retry path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CC-010: retryCancellation on CANCEL_FAILED_BILLING → ledger reversal retried, CANCELLED")
    void retryCancellation_happyPath_billingRetry() {
        SalesOrder order = draftOrder();
        order.setStatus(SalesOrderStatus.CANCEL_FAILED_BILLING);
        order.setCancellationIdempotencyKey("idem-key-1");
        order.setInvoiceId(INVOICE_ID);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRecordRepository.findByOrderId(ORDER_ID))
                .thenReturn(List.of(settledRecord(new BigDecimal("80.00"))));
        when(invoicingPort.reversePayment(eq(INVOICE_ID), eq(INTENT_ID), any(ReversePaymentCommand.class)))
                .thenReturn(new PaymentReversalResult(true, "REFUND", "Reversed"));

        CancellationResult result = orderCancellationService.retryCancellation(ORDER_ID, "idem-key-1");

        assertThat(result.status()).isEqualTo("CANCELLED");
        verify(invoicingPort).reversePayment(eq(INVOICE_ID), eq(INTENT_ID), any(ReversePaymentCommand.class));
    }

    @Test
    @DisplayName("CC-010b: retry fails again → CANCEL_REQUIRES_MANUAL_REVIEW + alert fact")
    void retryCancellation_failsAgain_parksForManualReview() {
        SalesOrder order = draftOrder();
        order.setStatus(SalesOrderStatus.CANCEL_FAILED_BILLING);
        order.setInvoiceId(INVOICE_ID);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRecordRepository.findByOrderId(ORDER_ID))
                .thenReturn(List.of(settledRecord(new BigDecimal("80.00"))));
        when(invoicingPort.reversePayment(eq(INVOICE_ID), eq(INTENT_ID), any(ReversePaymentCommand.class)))
                .thenReturn(new PaymentReversalResult(false, null, "still down"));

        assertThatThrownBy(() -> orderCancellationService.retryCancellation(ORDER_ID, "idem-key-2"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.CANCEL_REQUIRES_MANUAL_REVIEW);
        verify(orderDomainEventPublisher)
                .publishCancelReviewRequired(eq(order), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("CC-011: retryCancellation on already-CANCELLED order → IllegalStateException")
    void retryCancellation_wrongState_throwsIllegalState() {
        SalesOrder order = draftOrder();
        order.setStatus(SalesOrderStatus.CANCELLED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderCancellationService.retryCancellation(ORDER_ID, "idem-key-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("CC-012: cancelOrder omitting workOrderId preserves the persisted workOrderId")
    void cancelOrder_omitOptionalIds_preservesPersistedIds() {
        SalesOrder order = quotedOrderWithWorkorder();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancellationResult result = orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null));

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(order.getWorkOrderId()).isEqualTo(WORKORDER_ID);
        verify(workexecPort, never()).checkWorkorderStatus(any());
        verify(workexecPort, never()).cancelWorkorder(any(), any());
        verify(invoicingPort, never()).reversePayment(any(), any(), any());
    }
}
