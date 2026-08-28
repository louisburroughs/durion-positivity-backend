package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.dto.ReturnOrderSummary;
import com.positivity.order.internal.entity.ReturnOrder;
import com.positivity.order.internal.entity.ReturnOrderStatus;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.entity.SourceType;
import com.positivity.order.internal.exception.OverCapReturnException;
import com.positivity.order.internal.exception.WarrantyReturnRoutingException;
import com.positivity.order.internal.repository.ReturnOrderLineRepository;
import com.positivity.order.internal.repository.ReturnOrderRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.model.CreateReturnCommand;
import com.positivity.order.internal.service.model.ReturnLineCommand;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for returns & refunds (parity story F1, #1086): qty-cap invariant, returnability +
 * warranty routing, approval threshold, and idempotent creation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReturnOrderServiceImpl — parity story F1")
class ReturnOrderServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID LINE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private SalesOrderLineRepository salesOrderLineRepository;

    @Mock
    private ReturnOrderRepository returnOrderRepository;

    @Mock
    private ReturnOrderLineRepository returnOrderLineRepository;

    @Mock
    private com.positivity.order.internal.repository.OrderPaymentRecordRepository paymentRecordRepository;

    @Mock
    private com.positivity.order.internal.client.InvoicingPort invoicingPort;

    @Mock
    private com.positivity.order.internal.config.OrderDomainEventPublisher domainEventPublisher;

    private ReturnOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReturnOrderServiceImpl(
                salesOrderRepository,
                salesOrderLineRepository,
                returnOrderRepository,
                returnOrderLineRepository,
                paymentRecordRepository,
                invoicingPort,
                domainEventPublisher,
                CLOCK);
        ReflectionTestUtils.setField(service, "approvalThreshold", new BigDecimal("250.00"));
    }

    private SalesOrder completedOrder() {
        return SalesOrder.builder()
                .orderId(ORDER_ID)
                .orderNumber("SO-1")
                .status(SalesOrderStatus.COMPLETED)
                .build();
    }

    private SalesOrderLine soldLine(int qty, String lineTotal, Boolean returnable, SourceType sourceType) {
        return SalesOrderLine.builder()
                .orderLineId(LINE_ID)
                .itemSku("SKU-1")
                .itemDescription("Widget")
                .quantity(qty)
                .unitPrice(new BigDecimal("50.0000"))
                .lineTotal(new BigDecimal(lineTotal))
                .returnable(returnable)
                .sourceType(sourceType)
                .build();
    }

    private CreateReturnCommand command(int returnQty, String condition) {
        return new CreateReturnCommand(
                ORDER_ID,
                "ORIGINAL_TENDER",
                "DEFECT",
                List.of(new ReturnLineCommand(LINE_ID, returnQty, condition, null)),
                "idem-1");
    }

    private void stubOrder(SalesOrderLine line) {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(completedOrder()));
        when(salesOrderLineRepository.findByOrder_OrderId(ORDER_ID)).thenReturn(List.of(line));
        lenient()
                .when(salesOrderLineRepository.findByOrderLineIdForUpdate(LINE_ID))
                .thenReturn(Optional.of(line));
        lenient()
                .when(returnOrderRepository.findByCreationIdempotencyKey("idem-1"))
                .thenReturn(Optional.empty());
        lenient().when(returnOrderRepository.save(any())).thenAnswer(inv -> {
            ReturnOrder r = inv.getArgument(0);
            if (r.getReturnOrderId() == null) {
                r.setReturnOrderId(UUID.randomUUID());
            }
            return r;
        });
    }

    @Test
    @DisplayName("ROS-001: a within-cap return is created in RETURN_REQUESTED with a pro-rata refund")
    void createReturn_withinCap() {
        stubOrder(soldLine(2, "108.0000", null, null));
        when(returnOrderLineRepository.sumReturnedQty(eq(LINE_ID), anyList())).thenReturn(0);

        ReturnOrderSummary summary = service.createReturn(command(1, "RESTOCK"));

        assertThat(summary.status()).isEqualTo("RETURN_REQUESTED");
        assertThat(summary.lines()).hasSize(1);
        // 108.00 line total over 2 units -> 54.00 per unit
        assertThat(summary.lines().get(0).lineRefund()).isEqualByComparingTo("54.00");
        assertThat(summary.totalRefund()).isEqualByComparingTo("54.00");
    }

    @Test
    @DisplayName("ROS-002: an over-cap return is rejected listing the returnableQty")
    void createReturn_overCap() {
        stubOrder(soldLine(2, "108.0000", null, null));
        when(returnOrderLineRepository.sumReturnedQty(eq(LINE_ID), anyList())).thenReturn(2); // fully returned

        assertThatThrownBy(() -> service.createReturn(command(1, "RESTOCK")))
                .isInstanceOf(OverCapReturnException.class)
                .satisfies(ex -> {
                    OverCapReturnException oce = (OverCapReturnException) ex;
                    assertThat(oce.getOffendingLines()).hasSize(1);
                    assertThat(oce.getOffendingLines().get(0).returnableQty()).isZero();
                    assertThat(oce.getOffendingLines().get(0).requested()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("ROS-003: WARRANTY on a non-returnable workorder line routes to pos-warranty (422)")
    void createReturn_warrantyRouting() {
        stubOrder(soldLine(2, "108.0000", Boolean.FALSE, SourceType.WORKORDER));

        assertThatThrownBy(() -> service.createReturn(command(1, "WARRANTY")))
                .isInstanceOf(WarrantyReturnRoutingException.class);
    }

    @Test
    @DisplayName("ROS-004: RESTOCK on a non-returnable line is a plain validation error")
    void createReturn_nonReturnableRestock() {
        stubOrder(soldLine(2, "108.0000", Boolean.FALSE, SourceType.WORKORDER));

        assertThatThrownBy(() -> service.createReturn(command(1, "RESTOCK")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ROS-005: returns are only allowed against COMPLETED orders")
    void createReturn_notCompleted() {
        SalesOrder draft = completedOrder();
        draft.setStatus(SalesOrderStatus.DRAFT);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.createReturn(command(1, "RESTOCK"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ROS-006: a refund above the approval threshold lands in PENDING_APPROVAL")
    void createReturn_pendingApproval() {
        stubOrder(soldLine(10, "3000.0000", null, null)); // 300/unit
        when(returnOrderLineRepository.sumReturnedQty(eq(LINE_ID), anyList())).thenReturn(0);

        ReturnOrderSummary summary = service.createReturn(command(1, "RESTOCK")); // 300 > 250 threshold

        assertThat(summary.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(summary.totalRefund()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("ROS-007: a replayed idempotency key returns the original return")
    void createReturn_idempotentReplay() {
        ReturnOrder existing = ReturnOrder.builder()
                .returnOrderId(UUID.randomUUID())
                .originalOrderId(ORDER_ID)
                .refundMethod(com.positivity.order.internal.entity.RefundMethod.ORIGINAL_TENDER)
                .status(ReturnOrderStatus.RETURN_REQUESTED)
                .totalRefund(new BigDecimal("54.00"))
                .creationIdempotencyKey("idem-1")
                .createdBy("clerk")
                .updatedBy("clerk")
                .build();
        when(returnOrderRepository.findByCreationIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));

        ReturnOrderSummary summary = service.createReturn(command(1, "RESTOCK"));

        assertThat(summary.returnOrderId()).isEqualTo(existing.getReturnOrderId());
    }

    @Test
    @DisplayName("ROS-008: approve moves a pending return to RETURN_REQUESTED")
    void approveReturn_movesToRequested() {
        UUID id = UUID.randomUUID();
        ReturnOrder pending = ReturnOrder.builder()
                .returnOrderId(id)
                .originalOrderId(ORDER_ID)
                .refundMethod(com.positivity.order.internal.entity.RefundMethod.ORIGINAL_TENDER)
                .status(ReturnOrderStatus.PENDING_APPROVAL)
                .totalRefund(new BigDecimal("300.00"))
                .createdBy("clerk")
                .updatedBy("clerk")
                .build();
        when(returnOrderRepository.findById(id)).thenReturn(Optional.of(pending));
        when(returnOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReturnOrderSummary summary = service.approveReturn(id);

        assertThat(summary.status()).isEqualTo("RETURN_REQUESTED");
        assertThat(pending.getApprovedAt()).isEqualTo(Instant.parse("2026-07-24T12:00:00Z"));
    }

    @Test
    @DisplayName("ROS-009: reject moves a pending return to REJECTED and does not count toward the cap")
    void rejectReturn_movesToRejected() {
        UUID id = UUID.randomUUID();
        ReturnOrder pending = ReturnOrder.builder()
                .returnOrderId(id)
                .originalOrderId(ORDER_ID)
                .refundMethod(com.positivity.order.internal.entity.RefundMethod.ORIGINAL_TENDER)
                .status(ReturnOrderStatus.PENDING_APPROVAL)
                .totalRefund(new BigDecimal("300.00"))
                .createdBy("clerk")
                .updatedBy("clerk")
                .build();
        when(returnOrderRepository.findById(id)).thenReturn(Optional.of(pending));
        when(returnOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReturnOrderSummary summary = service.rejectReturn(id, "not eligible");

        assertThat(summary.status()).isEqualTo("REJECTED");
        assertThat(pending.getRejectionReason()).isEqualTo("not eligible");
    }

    @Test
    @DisplayName("ROS-010: duplicate return lines for the same sold line are rejected (422)")
    void createReturn_duplicateLinesRejected() {
        stubOrder(soldLine(5, "500.0000", null, null));
        CreateReturnCommand duplicated = new CreateReturnCommand(
                ORDER_ID,
                "ORIGINAL_TENDER",
                "DEFECT",
                List.of(
                        new ReturnLineCommand(LINE_ID, 2, "RESTOCK", null),
                        new ReturnLineCommand(LINE_ID, 2, "RESTOCK", null)),
                "idem-dup");
        lenient()
                .when(returnOrderRepository.findByCreationIdempotencyKey("idem-dup"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createReturn(duplicated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate return line");
    }

    // ── F2 saga (#1087) ──────────────────────────────────────────────────────

    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID INTENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private ReturnOrder requestedReturn(String refundMethod, UUID customerId) {
        ReturnOrder ro = ReturnOrder.builder()
                .returnOrderId(UUID.randomUUID())
                .originalOrderId(ORDER_ID)
                .originalInvoiceId(INVOICE_ID)
                .customerId(customerId)
                .refundMethod(com.positivity.order.internal.entity.RefundMethod.valueOf(refundMethod))
                .status(ReturnOrderStatus.RETURN_REQUESTED)
                .totalRefund(new BigDecimal("54.00"))
                .createdBy("clerk")
                .updatedBy("clerk")
                .build();
        return ro;
    }

    private com.positivity.order.internal.entity.OrderPaymentRecord settledCash(String amount) {
        return com.positivity.order.internal.entity.OrderPaymentRecord.builder()
                .orderId(ORDER_ID)
                .recordType(com.positivity.order.internal.entity.OrderPaymentRecord.RecordType.SETTLED)
                .paymentIntentId(INTENT_ID)
                .methodType("CASH")
                .amount(new BigDecimal(amount))
                .build();
    }

    @Test
    @DisplayName("SAGA-001: ORIGINAL_TENDER refund reverses the intent, emits the fact, completes")
    void process_originalTenderCompletes() {
        UUID id = UUID.randomUUID();
        ReturnOrder ro = requestedReturn("ORIGINAL_TENDER", null);
        ro.setReturnOrderId(id);
        when(returnOrderRepository.findById(id)).thenReturn(Optional.of(ro));
        when(returnOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRecordRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(settledCash("108.00")));
        when(invoicingPort.reversePayment(any(), any(), any()))
                .thenReturn(new com.positivity.order.internal.client.PaymentReversalResult(true, "REFUND", "ok"));

        ReturnOrderSummary summary = service.processReturn(id);

        assertThat(summary.status()).isEqualTo("COMPLETED");
        org.mockito.Mockito.verify(invoicingPort).reversePayment(eq(INVOICE_ID), eq(INTENT_ID), any());
        org.mockito.Mockito.verify(domainEventPublisher).publishOrderReturned(ro);
        assertThat(ro.getReturnedAt()).isEqualTo(Instant.parse("2026-07-24T12:00:00Z"));
    }

    @Test
    @DisplayName("SAGA-002: a refund failure parks at REFUND_FAILED before any stock signal")
    void process_refundFailureHaltsBeforeStock() {
        UUID id = UUID.randomUUID();
        ReturnOrder ro = requestedReturn("ORIGINAL_TENDER", null);
        ro.setReturnOrderId(id);
        when(returnOrderRepository.findById(id)).thenReturn(Optional.of(ro));
        when(returnOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRecordRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(settledCash("108.00")));
        when(invoicingPort.reversePayment(any(), any(), any()))
                .thenReturn(new com.positivity.order.internal.client.PaymentReversalResult(
                        false, "REFUND", "gateway down"));

        assertThatThrownBy(() -> service.processReturn(id)).isInstanceOf(IllegalStateException.class);

        assertThat(ro.getStatus()).isEqualTo(ReturnOrderStatus.REFUND_FAILED);
        org.mockito.Mockito.verify(domainEventPublisher, org.mockito.Mockito.never())
                .publishOrderReturned(any());
    }

    @Test
    @DisplayName("SAGA-003: retry from REFUND_FAILED re-runs the refund and completes")
    void retry_fromRefundFailed() {
        UUID id = UUID.randomUUID();
        ReturnOrder ro = requestedReturn("ORIGINAL_TENDER", null);
        ro.setReturnOrderId(id);
        ro.setStatus(ReturnOrderStatus.REFUND_FAILED);
        when(returnOrderRepository.findById(id)).thenReturn(Optional.of(ro));
        when(returnOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRecordRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(settledCash("108.00")));
        when(invoicingPort.reversePayment(any(), any(), any()))
                .thenReturn(new com.positivity.order.internal.client.PaymentReversalResult(true, "REFUND", "ok"));

        ReturnOrderSummary summary = service.retryReturn(id);

        assertThat(summary.status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("SAGA-004: STORE_CREDIT without a customer parks at REFUND_FAILED")
    void process_storeCreditRequiresCustomer() {
        UUID id = UUID.randomUUID();
        ReturnOrder ro = requestedReturn("STORE_CREDIT", null);
        ro.setReturnOrderId(id);
        when(returnOrderRepository.findById(id)).thenReturn(Optional.of(ro));
        when(returnOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.processReturn(id)).isInstanceOf(IllegalStateException.class);
        assertThat(ro.getStatus()).isEqualTo(ReturnOrderStatus.REFUND_FAILED);
    }

    @Test
    @DisplayName("SAGA-005: processing an already-COMPLETED return is an idempotent no-op")
    void process_completedIsNoOp() {
        UUID id = UUID.randomUUID();
        ReturnOrder ro = requestedReturn("ORIGINAL_TENDER", null);
        ro.setReturnOrderId(id);
        ro.setStatus(ReturnOrderStatus.COMPLETED);
        when(returnOrderRepository.findById(id)).thenReturn(Optional.of(ro));

        ReturnOrderSummary summary = service.processReturn(id);

        assertThat(summary.status()).isEqualTo("COMPLETED");
        org.mockito.Mockito.verify(invoicingPort, org.mockito.Mockito.never()).reversePayment(any(), any(), any());
    }
}
