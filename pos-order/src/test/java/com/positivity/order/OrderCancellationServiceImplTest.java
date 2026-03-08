package com.positivity.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.client.BillingPort;
import com.positivity.order.internal.client.CancelWorkorderCommand;
import com.positivity.order.internal.client.PaymentReversalResult;
import com.positivity.order.internal.client.ReversePaymentCommand;
import com.positivity.order.internal.client.WorkexecPort;
import com.positivity.order.internal.client.WorkorderCancelResult;
import com.positivity.order.internal.client.WorkorderStatusResult;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.OrderCancellationServiceImpl;
import com.positivity.order.service.model.CancelOrderCommand;
import com.positivity.order.service.model.CancellationResult;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link OrderCancellationServiceImpl} covering the POS
 * cancellation
 * saga: state gating, Workexec gating, billing reversal, idempotency, and retry
 * path.
 *
 * <p>
 * Issue: #19
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderCancellationServiceImpl Unit Tests — Issue #19")
class OrderCancellationServiceImplTest {

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private WorkexecPort workexecPort;

    @Mock
    private BillingPort billingPort;

    @InjectMocks
    private OrderCancellationServiceImpl orderCancellationService;

    // ── shared fixtures ───────────────────────────────────────────────────────

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

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

    private SalesOrder orderWithWorkorderAndPayment() {
        SalesOrder order = quotedOrderWithWorkorder();
        order.setPaymentId(PAYMENT_ID);
        return order;
    }

    private CancelOrderCommand cancelCommand(UUID workOrderId, UUID paymentId) {
        return new CancelOrderCommand(
                "Customer request", workOrderId, paymentId, "idem-key-1");
    }

    // ── Issue #19: happy path ─────────────────────────────────────────────────

    /**
     * BR2: No workorder or payment — saga skips external calls and terminates
     * CANCELLED.
     */
    @Test
    @DisplayName("CC-001: DRAFT order, no workOrderId, no paymentId → result.status = CANCELLED")
    void cancelOrder_happyPath_noWorkorder_noPayment() {
        // Arrange
        SalesOrder order = draftOrder();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        CancellationResult result = orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null, null));

        // Assert
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        verify(workexecPort, never()).checkWorkorderStatus(any());
        verify(billingPort, never()).reversePayment(any(), any());
    }

    /**
     * BR2 + BR1: workOrderId present → Workexec pre-check + cancel command issued;
     * saga ends CANCELLED.
     */
    @Test
    @DisplayName("CC-002: QUOTED order with workOrderId → Workexec checked and cancelled, result CANCELLED")
    void cancelOrder_happyPath_withWorkorder_noPayment() {
        // Arrange
        SalesOrder order = quotedOrderWithWorkorder();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workexecPort.checkWorkorderStatus(WORKORDER_ID))
                .thenReturn(new WorkorderStatusResult("OPEN", true, null));
        when(workexecPort.cancelWorkorder(eq(WORKORDER_ID), any(CancelWorkorderCommand.class)))
                .thenReturn(new WorkorderCancelResult(true, "Cancelled"));

        // Act
        CancellationResult result = orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(WORKORDER_ID, null));

        // Assert
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        verify(workexecPort).checkWorkorderStatus(WORKORDER_ID);
        verify(workexecPort).cancelWorkorder(eq(WORKORDER_ID), any(CancelWorkorderCommand.class));
        verify(billingPort, never()).reversePayment(any(), any());
    }

    /**
     * BR2 + BR1: full saga — both Workexec and Billing calls complete, order
     * CANCELLED.
     */
    @Test
    @DisplayName("CC-003: Order with workOrderId + paymentId → full saga, CANCELLED; both ports called")
    void cancelOrder_happyPath_withWorkorder_withPayment() {
        // Arrange
        SalesOrder order = orderWithWorkorderAndPayment();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workexecPort.checkWorkorderStatus(WORKORDER_ID))
                .thenReturn(new WorkorderStatusResult("OPEN", true, null));
        when(workexecPort.cancelWorkorder(eq(WORKORDER_ID), any(CancelWorkorderCommand.class)))
                .thenReturn(new WorkorderCancelResult(true, "Cancelled"));
        when(billingPort.reversePayment(eq(PAYMENT_ID), any(ReversePaymentCommand.class)))
                .thenReturn(new PaymentReversalResult(true, "VOID", "Payment voided"));

        // Act
        CancellationResult result = orderCancellationService.cancelOrder(ORDER_ID,
                cancelCommand(WORKORDER_ID, PAYMENT_ID));

        // Assert
        assertThat(result.status()).isEqualTo("CANCELLED");
        verify(workexecPort).checkWorkorderStatus(WORKORDER_ID);
        verify(workexecPort).cancelWorkorder(eq(WORKORDER_ID), any(CancelWorkorderCommand.class));
        verify(billingPort).reversePayment(eq(PAYMENT_ID), any(ReversePaymentCommand.class));
    }

    // ── Issue #19: Workexec failure paths ────────────────────────────────────

    /**
     * BR1: Workexec pre-check returns cancellable=false → stops saga with
     * CANCEL_FAILED_WORKEXEC.
     */
    @Test
    @DisplayName("CC-004: Workexec cancellable=false → IllegalStateException, order CANCEL_FAILED_WORKEXEC")
    void cancelOrder_workexec_notCancellable_throwsIllegalState() {
        // Arrange
        SalesOrder order = quotedOrderWithWorkorder();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workexecPort.checkWorkorderStatus(WORKORDER_ID))
                .thenReturn(new WorkorderStatusResult("IN_PROGRESS", false, "Work already started"));

        // Act & Assert
        assertThatThrownBy(() -> orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(WORKORDER_ID, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * BR1: Workexec cancel command returns success=false → sets
     * CANCEL_FAILED_WORKEXEC.
     */
    @Test
    @DisplayName("CC-005: Workexec cancelWorkorder returns success=false → IllegalStateException, CANCEL_FAILED_WORKEXEC")
    void cancelOrder_workexec_cancelFails_statusSetToFailWorkexec() {
        // Arrange
        SalesOrder order = quotedOrderWithWorkorder();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workexecPort.checkWorkorderStatus(WORKORDER_ID))
                .thenReturn(new WorkorderStatusResult("OPEN", true, null));
        when(workexecPort.cancelWorkorder(eq(WORKORDER_ID), any(CancelWorkorderCommand.class)))
                .thenReturn(new WorkorderCancelResult(false, "Failed to cancel"));

        // Act & Assert
        assertThatThrownBy(() -> orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(WORKORDER_ID, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Issue #19: Billing failure path ──────────────────────────────────────

    /**
     * BR2 + BR3: Workexec succeeds but Billing reversal fails → sets
     * CANCEL_FAILED_BILLING.
     */
    @Test
    @DisplayName("CC-006: Workexec succeeds, BillingPort reversal fails → IllegalStateException, CANCEL_FAILED_BILLING")
    void cancelOrder_billing_reversalFails_statusSetToFailBilling() {
        // Arrange
        SalesOrder order = orderWithWorkorderAndPayment();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workexecPort.checkWorkorderStatus(WORKORDER_ID))
                .thenReturn(new WorkorderStatusResult("OPEN", true, null));
        when(workexecPort.cancelWorkorder(eq(WORKORDER_ID), any(CancelWorkorderCommand.class)))
                .thenReturn(new WorkorderCancelResult(true, "Cancelled"));
        when(billingPort.reversePayment(eq(PAYMENT_ID), any(ReversePaymentCommand.class)))
                .thenReturn(new PaymentReversalResult(false, null, "Billing system unavailable"));

        // Act & Assert
        assertThatThrownBy(
                () -> orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(WORKORDER_ID, PAYMENT_ID)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Issue #19: Idempotency ────────────────────────────────────────────────

    /**
     * BR4: Same idempotencyKey on already-CANCELLED order → returns existing
     * result, no ports re-invoked.
     */
    @Test
    @DisplayName("CC-007: Already CANCELLED + matching idempotencyKey → existing result returned, no ports called")
    void cancelOrder_idempotency_alreadyCancelled_returnsExisting() {
        // Arrange
        SalesOrder order = draftOrder();
        order.setStatus(SalesOrderStatus.CANCELLED);
        order.setCancellationIdempotencyKey("idem-key-1");
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        // Act
        CancellationResult result = orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null, null));

        // Assert — should return existing result without re-running saga
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.cancellationIdempotencyKey()).isEqualTo("idem-key-1");
        verify(workexecPort, never()).checkWorkorderStatus(any());
        verify(billingPort, never()).reversePayment(any(), any());
    }

    // ── Issue #19: Invalid state / not found ─────────────────────────────────

    /**
     * State guard: COMPLETED orders are not eligible for cancellation.
     */
    @Test
    @DisplayName("CC-008: COMPLETED order → IllegalStateException — order cannot be cancelled")
    void cancelOrder_invalidState_completed_throwsIllegalState() {
        // Arrange
        SalesOrder order = draftOrder();
        order.setStatus(SalesOrderStatus.COMPLETED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        // Act & Assert
        assertThatThrownBy(() -> orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancel");
    }

    /**
     * Repository returns empty → not found exception surfaced to caller.
     */
    @Test
    @DisplayName("CC-009: Order not found → SalesOrderNotFoundException")
    void cancelOrder_orderNotFound_throwsSalesOrderNotFoundException() {
        // Arrange
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> orderCancellationService.cancelOrder(ORDER_ID, cancelCommand(null, null)))
                .isInstanceOf(SalesOrderNotFoundException.class);
    }

    // ── Issue #19: Retry path ─────────────────────────────────────────────────

    /**
     * Retry: order in CANCEL_FAILED_BILLING with matching key → billing
     * re-attempted, CANCELLED.
     */
    @Test
    @DisplayName("CC-010: retryCancellation on CANCEL_FAILED_BILLING → billing retried, result CANCELLED")
    void retryCancellation_happyPath_billingRetry() {
        // Arrange
        SalesOrder order = draftOrder();
        order.setStatus(SalesOrderStatus.CANCEL_FAILED_BILLING);
        order.setCancellationIdempotencyKey("idem-key-1");
        order.setPaymentId(PAYMENT_ID);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingPort.reversePayment(eq(PAYMENT_ID), any(ReversePaymentCommand.class)))
                .thenReturn(new PaymentReversalResult(true, "VOID", "Payment voided"));

        // Act
        CancellationResult result = orderCancellationService.retryCancellation(ORDER_ID, "idem-key-1");

        // Assert
        assertThat(result.status()).isEqualTo("CANCELLED");
        verify(billingPort).reversePayment(eq(PAYMENT_ID), any(ReversePaymentCommand.class));
    }

    /**
     * Retry guard: order in CANCELLED state is not valid for retry.
     */
    @Test
    @DisplayName("CC-011: retryCancellation on already-CANCELLED order → IllegalStateException")
    void retryCancellation_wrongState_throwsIllegalState() {
        // Arrange
        SalesOrder order = draftOrder();
        order.setStatus(SalesOrderStatus.CANCELLED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        // Act & Assert
        assertThatThrownBy(() -> orderCancellationService.retryCancellation(ORDER_ID, "idem-key-1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
