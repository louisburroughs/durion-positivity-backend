package com.positivity.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.client.PaymentReversalResult;
import com.positivity.order.internal.client.ReversePaymentCommand;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.RefundMethod;
import com.positivity.order.internal.entity.ReturnOrder;
import com.positivity.order.internal.entity.ReturnOrderStatus;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.entity.SourceType;
import com.positivity.order.internal.repository.ReturnOrderLineRepository;
import com.positivity.order.internal.repository.ReturnOrderRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.ReturnOrderServiceImpl;
import com.positivity.order.service.model.ReturnableLineView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The returnable-lines read model, and the status guards on every return transition.
 *
 * <h2>Why this test exists</h2>
 *
 * {@code returnableLines} had no branch coverage at all — 0 of 10 — despite being the read model a
 * counter clerk is shown before taking anything back. If it over-reports, the clerk is told an item
 * can be returned when policy says it cannot, or is offered more units than remain against the cap;
 * either way the refund is agreed with the customer before the write path rejects it. The status
 * guards on approve, reject, process and retry were in the same position: only their happy arms
 * were exercised, so nothing pinned that a return cannot be approved twice or refunded from the
 * wrong state.
 *
 * <p>Written against {@code createReturn} as it stood before that method was split, so that the
 * split could be shown to preserve behaviour rather than merely be believed to.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReturnOrderServiceImpl — read model and status guards")
class ReturnOrderReadModelAndGuardsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID LINE_A = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID LINE_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID RETURN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

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

    @Nested
    @DisplayName("returnableLines")
    class ReturnableLines {

        @Test
        @DisplayName("reports the remaining quantity per line, net of what has already been returned")
        void reportsRemainderNetOfPriorReturns() {
            stubCompletedOrder(line(LINE_A, 5, true, SourceType.ESTIMATE), line(LINE_B, 3, true, SourceType.ESTIMATE));
            when(returnOrderLineRepository.sumReturnedQtyByLine(anyList(), any()))
                    .thenReturn(List.<Object[]>of(new Object[] {LINE_A, 2}));

            List<ReturnableLineView> views = service.returnableLines(ORDER_ID);

            assertThat(views).hasSize(2);
            assertThat(views.get(0).soldQty()).isEqualTo(5);
            assertThat(views.get(0).alreadyReturned()).isEqualTo(2);
            assertThat(views.get(0).returnableQty()).isEqualTo(3);
            assertThat(views.get(0).returnable()).isTrue();
            // A line with no prior returns is not missing from the read model, it is simply at zero.
            assertThat(views.get(1).alreadyReturned()).isZero();
            assertThat(views.get(1).returnableQty()).isEqualTo(3);
        }

        @Test
        @DisplayName("a non-returnable line reports zero returnable, not its remaining quantity")
        void nonReturnableLineReportsZero() {
            stubCompletedOrder(line(LINE_A, 5, false, SourceType.ESTIMATE));
            when(returnOrderLineRepository.sumReturnedQtyByLine(anyList(), any()))
                    .thenReturn(List.of());

            ReturnableLineView view = service.returnableLines(ORDER_ID).getFirst();

            // The remaining quantity is irrelevant once policy says the line cannot come back.
            // Reporting 5 here would put the clerk in front of a customer promising a refund the
            // write path is going to refuse.
            assertThat(view.returnable()).isFalse();
            assertThat(view.returnableQty()).isZero();
            assertThat(view.soldQty()).isEqualTo(5);
        }

        @Test
        @DisplayName("a fully returned line reports zero rather than a negative remainder")
        void overReturnedLineFloorsAtZero() {
            stubCompletedOrder(line(LINE_A, 2, true, SourceType.ESTIMATE));
            // More returned than sold should not be possible, but if the data says so the read
            // model must not offer a negative quantity to the caller.
            when(returnOrderLineRepository.sumReturnedQtyByLine(anyList(), any()))
                    .thenReturn(List.<Object[]>of(new Object[] {LINE_A, 3}));

            assertThat(service.returnableLines(ORDER_ID).getFirst().returnableQty())
                    .isZero();
        }

        @Test
        @DisplayName("a workorder-sourced line with no explicit flag is not returnable at the counter")
        void workorderSourcedLineIsNotCounterReturnable() {
            stubCompletedOrder(line(LINE_A, 1, null, SourceType.WORKORDER));
            when(returnOrderLineRepository.sumReturnedQtyByLine(anyList(), any()))
                    .thenReturn(List.of());

            // Warranty claims route to pos-warranty (resolved Q6), so the counter must not offer
            // this line at all.
            assertThat(service.returnableLines(ORDER_ID).getFirst().returnable())
                    .isFalse();
        }

        @Test
        @DisplayName("an order with no lines does not query the returned-quantity read model")
        void orderWithNoLinesSkipsTheAggregateQuery() {
            when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(completedOrder()));
            when(salesOrderLineRepository.findByOrder_OrderId(ORDER_ID)).thenReturn(List.of());

            // An `in ()` against an empty id list is a query with no possible result.
            assertThat(service.returnableLines(ORDER_ID)).isEmpty();
        }

        @Test
        @DisplayName("an order that is not COMPLETED has no returnable lines to report")
        void nonCompletedOrderIsRejected() {
            SalesOrder open = SalesOrder.builder()
                    .orderId(ORDER_ID)
                    .orderNumber("SO-1")
                    .status(SalesOrderStatus.DRAFT)
                    .build();
            when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(open));

            assertThatThrownBy(() -> service.returnableLines(ORDER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("COMPLETED");
        }
    }

    @Nested
    @DisplayName("status guards")
    class StatusGuards {

        @Test
        @DisplayName("only a PENDING_APPROVAL return can be approved")
        void approveRejectsWrongStatus() {
            stubReturn(ReturnOrderStatus.RETURN_REQUESTED);

            assertThatThrownBy(() -> service.approveReturn(RETURN_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PENDING_APPROVAL");
        }

        @Test
        @DisplayName("only a PENDING_APPROVAL return can be rejected")
        void rejectRejectsWrongStatus() {
            stubReturn(ReturnOrderStatus.COMPLETED);

            assertThatThrownBy(() -> service.rejectReturn(RETURN_ID, "changed mind"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PENDING_APPROVAL");
        }

        @Test
        @DisplayName("the saga refuses to run from a status other than RETURN_REQUESTED")
        void processRejectsWrongStatus() {
            stubReturn(ReturnOrderStatus.PENDING_APPROVAL);

            // A return still awaiting approval must not be refundable by calling process directly.
            assertThatThrownBy(() -> service.processReturn(RETURN_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RETURN_REQUESTED");
        }

        @Test
        @DisplayName("processing an already-completed return is a no-op, not an error")
        void processOnCompletedIsIdempotent() {
            stubReturn(ReturnOrderStatus.COMPLETED);

            // Idempotent on purpose: a retried call must not refund a second time.
            assertThat(service.processReturn(RETURN_ID).status()).isEqualTo(ReturnOrderStatus.COMPLETED.name());
        }

        @Test
        @DisplayName("retry is only allowed from REFUND_FAILED")
        void retryRejectsWrongStatus() {
            stubReturn(ReturnOrderStatus.RETURN_REQUESTED);

            assertThatThrownBy(() -> service.retryReturn(RETURN_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("REFUND_FAILED");
        }

        @Test
        @DisplayName("retrying an already-completed return is a no-op, not a second refund")
        void retryOnCompletedIsIdempotent() {
            stubReturn(ReturnOrderStatus.COMPLETED);

            assertThat(service.retryReturn(RETURN_ID).status()).isEqualTo(ReturnOrderStatus.COMPLETED.name());
        }
    }

    @Nested
    @DisplayName("refunds")
    class Refunds {

        private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        private static final UUID INTENT_BIG = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        private static final UUID INTENT_SMALL = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

        @Test
        @DisplayName("original tender is reversed largest intent first, and only up to the refund total")
        void reversesLargestIntentFirstUpToTheTotal() {
            stubRefundableReturn("120.0000", RefundMethod.ORIGINAL_TENDER, INVOICE_ID);
            when(paymentRecordRepository.findByOrderId(ORDER_ID))
                    .thenReturn(List.of(settled(INTENT_SMALL, "50.0000"), settled(INTENT_BIG, "100.0000")));
            when(invoicingPort.reversePayment(any(), any(), any()))
                    .thenReturn(new PaymentReversalResult(true, "REFUND", "ok"));

            service.processReturn(RETURN_ID);

            ArgumentCaptor<ReversePaymentCommand> commands = ArgumentCaptor.forClass(ReversePaymentCommand.class);
            ArgumentCaptor<UUID> intents = ArgumentCaptor.forClass(UUID.class);
            verify(invoicingPort, times(2)).reversePayment(eq(INVOICE_ID), intents.capture(), commands.capture());

            // Largest first, and the second intent is drawn on only for the 20.00 shortfall --
            // taking its full 50.00 would refund more than the customer is owed.
            assertThat(intents.getAllValues()).containsExactly(INTENT_BIG, INTENT_SMALL);
            assertThat(commands.getAllValues().get(0).amount()).isEqualByComparingTo("100.0000");
            assertThat(commands.getAllValues().get(1).amount()).isEqualByComparingTo("20.0000");
        }

        @Test
        @DisplayName("an intent whose settlement has already been reversed is not drawn on again")
        void fullyReversedIntentIsSkipped() {
            stubRefundableReturn("50.0000", RefundMethod.ORIGINAL_TENDER, INVOICE_ID);
            when(paymentRecordRepository.findByOrderId(ORDER_ID))
                    .thenReturn(List.of(
                            settled(INTENT_BIG, "100.0000"),
                            reversed(INTENT_BIG, "100.0000"),
                            settled(INTENT_SMALL, "50.0000"),
                            // A record with no intent cannot be reversed against anything.
                            settled(null, "999.0000")));
            when(invoicingPort.reversePayment(any(), any(), any()))
                    .thenReturn(new PaymentReversalResult(true, "REFUND", "ok"));

            service.processReturn(RETURN_ID);

            // Net zero on INTENT_BIG means there is nothing left there to refund; reversing it a
            // second time would pay the customer money the business never received.
            verify(invoicingPort, times(1)).reversePayment(eq(INVOICE_ID), eq(INTENT_SMALL), any());
        }

        @Test
        @DisplayName("a failed reversal parks the return as REFUND_FAILED rather than completing it")
        void failedReversalParksTheReturn() {
            ReturnOrder returnOrder = stubRefundableReturn("50.0000", RefundMethod.ORIGINAL_TENDER, INVOICE_ID);
            when(paymentRecordRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(settled(INTENT_BIG, "100.0000")));
            when(invoicingPort.reversePayment(any(), any(), any()))
                    .thenReturn(new PaymentReversalResult(false, "REFUND", "gateway declined"));

            assertThatThrownBy(() -> service.processReturn(RETURN_ID)).isInstanceOf(IllegalStateException.class);

            // REFUND_FAILED is the retryable state; completing here would record a refund the
            // customer never received.
            assertThat(returnOrder.getStatus()).isEqualTo(ReturnOrderStatus.REFUND_FAILED);
            assertThat(returnOrder.getFailureReason()).contains("gateway declined");
        }

        @Test
        @DisplayName("too little settled tender to cover the refund parks the return")
        void insufficientSettledTenderParksTheReturn() {
            ReturnOrder returnOrder = stubRefundableReturn("200.0000", RefundMethod.ORIGINAL_TENDER, INVOICE_ID);
            when(paymentRecordRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(settled(INTENT_BIG, "100.0000")));
            when(invoicingPort.reversePayment(any(), any(), any()))
                    .thenReturn(new PaymentReversalResult(true, "REFUND", "ok"));

            assertThatThrownBy(() -> service.processReturn(RETURN_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Insufficient settled original tender");

            // The 100.00 that did reverse is not rolled back here -- the transaction is, by the
            // exception. Parking records why, so a retry does not start from a clean-looking state.
            assertThat(returnOrder.getStatus()).isEqualTo(ReturnOrderStatus.REFUND_FAILED);
        }

        @Test
        @DisplayName("no invoice on the original order parks the return")
        void missingInvoiceParksTheReturn() {
            ReturnOrder returnOrder = stubRefundableReturn("50.0000", RefundMethod.ORIGINAL_TENDER, null);

            assertThatThrownBy(() -> service.processReturn(RETURN_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No invoice");

            assertThat(returnOrder.getStatus()).isEqualTo(ReturnOrderStatus.REFUND_FAILED);
        }

        @Test
        @DisplayName("a zero-value return completes without touching the payment gateway")
        void zeroRefundSkipsReversal() {
            stubRefundableReturn("0.0000", RefundMethod.ORIGINAL_TENDER, INVOICE_ID);

            assertThat(service.processReturn(RETURN_ID).status()).isEqualTo(ReturnOrderStatus.COMPLETED.name());
            verifyNoInteractions(invoicingPort);
        }

        @Test
        @DisplayName("a store-credit refund with no customer on the return is parked")
        void storeCreditWithoutCustomerParksTheReturn() {
            ReturnOrder returnOrder = stubRefundableReturn("50.0000", RefundMethod.STORE_CREDIT, INVOICE_ID);

            assertThatThrownBy(() -> service.processReturn(RETURN_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires a customer");

            assertThat(returnOrder.getStatus()).isEqualTo(ReturnOrderStatus.REFUND_FAILED);
        }

        @Test
        @DisplayName("a store-credit refund with a customer completes without a gateway reversal")
        void storeCreditWithCustomerCompletes() {
            ReturnOrder returnOrder = stubRefundableReturn("50.0000", RefundMethod.STORE_CREDIT, INVOICE_ID);
            returnOrder.setCustomerId(UUID.fromString("00000000-0000-0000-0000-0000000000f1"));

            assertThat(service.processReturn(RETURN_ID).status()).isEqualTo(ReturnOrderStatus.COMPLETED.name());
            verifyNoInteractions(invoicingPort);
        }

        private ReturnOrder stubRefundableReturn(String totalRefund, RefundMethod method, UUID invoiceId) {
            ReturnOrder returnOrder = ReturnOrder.builder()
                    .returnOrderId(RETURN_ID)
                    .originalOrderId(ORDER_ID)
                    .originalOrderNumber("SO-1")
                    .originalInvoiceId(invoiceId)
                    .status(ReturnOrderStatus.RETURN_REQUESTED)
                    .refundMethod(method)
                    .totalRefund(new BigDecimal(totalRefund))
                    .build();
            when(returnOrderRepository.findById(RETURN_ID)).thenReturn(Optional.of(returnOrder));
            lenient().when(returnOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            return returnOrder;
        }

        private OrderPaymentRecord settled(UUID intentId, String amount) {
            return record(OrderPaymentRecord.RecordType.SETTLED, intentId, amount);
        }

        private OrderPaymentRecord reversed(UUID intentId, String amount) {
            return record(OrderPaymentRecord.RecordType.REVERSED, intentId, amount);
        }

        private OrderPaymentRecord record(OrderPaymentRecord.RecordType type, UUID intentId, String amount) {
            return OrderPaymentRecord.builder()
                    .orderId(ORDER_ID)
                    .recordType(type)
                    .paymentIntentId(intentId)
                    .amount(new BigDecimal(amount))
                    .build();
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private void stubReturn(ReturnOrderStatus status) {
        ReturnOrder returnOrder = ReturnOrder.builder()
                .returnOrderId(RETURN_ID)
                .originalOrderId(ORDER_ID)
                .originalOrderNumber("SO-1")
                .status(status)
                .refundMethod(RefundMethod.ORIGINAL_TENDER)
                .totalRefund(new BigDecimal("10.0000"))
                .build();
        when(returnOrderRepository.findById(RETURN_ID)).thenReturn(Optional.of(returnOrder));
    }

    private void stubCompletedOrder(SalesOrderLine... lines) {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(completedOrder()));
        when(salesOrderLineRepository.findByOrder_OrderId(ORDER_ID)).thenReturn(List.of(lines));
    }

    private static SalesOrder completedOrder() {
        return SalesOrder.builder()
                .orderId(ORDER_ID)
                .orderNumber("SO-1")
                .status(SalesOrderStatus.COMPLETED)
                .build();
    }

    private static SalesOrderLine line(UUID lineId, int qty, Boolean returnable, SourceType sourceType) {
        return SalesOrderLine.builder()
                .orderLineId(lineId)
                .itemSku("SKU-" + qty)
                .itemDescription("Widget")
                .quantity(qty)
                .unitPrice(new BigDecimal("50.0000"))
                .lineTotal(new BigDecimal("50.0000").multiply(BigDecimal.valueOf(qty)))
                .returnable(returnable)
                .sourceType(sourceType)
                .build();
    }
}
