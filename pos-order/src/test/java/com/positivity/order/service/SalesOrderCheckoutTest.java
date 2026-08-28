package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.client.InventoryPort;
import com.positivity.order.internal.client.InventoryResult;
import com.positivity.order.internal.client.InvoiceRef;
import com.positivity.order.internal.client.InvoicingPort;
import com.positivity.order.internal.client.PricingPort;
import com.positivity.order.internal.client.PricingQuote;
import com.positivity.order.internal.client.SourceDocumentPort;
import com.positivity.order.internal.entity.CustomerValidationStatus;
import com.positivity.order.internal.entity.FulfillmentStatus;
import com.positivity.order.internal.entity.PriceSource;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.CartIdempotencyConflictException;
import com.positivity.order.internal.exception.InvalidCustomerException;
import com.positivity.order.internal.exception.InvoicingUnavailableException;
import com.positivity.order.internal.repository.OrderStatusHistoryRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.model.CheckoutResult;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Optional;
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

/**
 * Unit tests for the checkout flow (parity story C1, #1071): validation gates, the
 * DRAFT|QUOTED → PENDING_PAYMENT freeze, the synchronous invoice handshake, and idempotent replay.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SalesOrderServiceImpl.checkout — parity story C1")
class SalesOrderCheckoutTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private SalesOrderLineRepository salesOrderLineRepository;

    @Mock
    private PricingPort pricingPort;

    @Mock
    private InventoryPort inventoryPort;

    @Mock
    private SourceDocumentPort sourceDocumentPort;

    @Mock
    private com.positivity.order.internal.client.CustomerPort customerPort;

    @Mock
    private InvoicingPort invoicingPort;

    @Mock
    private com.positivity.order.internal.repository.ExtProductRepository extProductRepository;

    @Mock
    private com.positivity.order.internal.repository.ExtCustomerRepository extCustomerRepository;

    @Mock
    private com.positivity.order.internal.repository.ExtBillingRulesRepository extBillingRulesRepository;

    @Mock
    private com.positivity.order.internal.repository.OrderPaymentRecordRepository orderPaymentRecordRepository;

    @Mock
    private com.positivity.order.internal.repository.RegisterSessionRepository registerSessionRepository;

    @Mock
    private com.positivity.order.internal.config.OrderDomainEventPublisher orderDomainEventPublisher;

    @Mock
    private com.positivity.order.internal.service.OrderNumberService orderNumberService;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private com.positivity.order.internal.service.OrderTaxService orderTaxService;

    private SalesOrderServiceImpl salesOrderService;

    /**
     * CAP #1315: checkout registers demand through this publisher, which is absent whenever Kafka
     * is disabled. These unit tests exercise the labelling half of the gate, so they supply the
     * absent case — the publishing half is covered in SalesOrderCheckoutReservationTest.
     */
    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<
                    com.positivity.order.internal.config.InventoryCommandPublisher>
            inventoryCommandPublisherProvider =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);

    @BeforeEach
    void setUp() {
        salesOrderService = new SalesOrderServiceImpl(
                salesOrderRepository,
                salesOrderLineRepository,
                pricingPort,
                inventoryPort,
                sourceDocumentPort,
                customerPort,
                invoicingPort,
                extProductRepository,
                extCustomerRepository,
                extBillingRulesRepository,
                orderPaymentRecordRepository,
                registerSessionRepository,
                orderDomainEventPublisher,
                new OrderStateMachine(orderStatusHistoryRepository, java.time.Clock.systemUTC()),
                orderNumberService,
                new OrderTotalsCalculator(),
                orderTaxService,
                inventoryCommandPublisherProvider,
                java.time.Clock.systemUTC());
        when(salesOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue(anyString()))
                .thenReturn(java.util.Optional.empty());
        when(orderPaymentRecordRepository.findByOrderId(any())).thenReturn(java.util.List.of());
        when(salesOrderRepository.findByCheckoutIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(inventoryPort.checkAvailability(anyString(), any(), any()))
                .thenReturn(new InventoryResult(true, BigDecimal.valueOf(999)));
        when(pricingPort.quoteForSku(anyString(), anyInt(), any(), any()))
                .thenReturn(new PricingQuote(
                        PricingQuote.Status.PRICED, money("50.00"), UUID.randomUUID(), "Widget", null, null));
        when(invoicingPort.createInvoiceForOrder(any()))
                .thenReturn(new InvoiceRef(INVOICE_ID, "INV-1", "DRAFT", money("100.00"), false));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(4, RoundingMode.HALF_UP);
    }

    private SalesOrder draftOrderWithLine() {
        SalesOrder order = SalesOrder.builder()
                .orderId(ORDER_ID)
                .clerkId("clerk-1")
                .terminalId("terminal-1")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(money("100.00"))
                .grandTotal(money("100.00"))
                .lines(new ArrayList<>())
                .createdBy("clerk-1")
                .updatedBy("clerk-1")
                .build();
        SalesOrderLine line = SalesOrderLine.builder()
                .orderLineId(UUID.randomUUID())
                .order(order)
                .itemSku("SKU-1")
                .itemDescription("Widget")
                .quantity(2)
                .unitPrice(money("50.00"))
                .lineSubtotal(money("100.00"))
                .taxAmount(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                .lineTotal(money("100.00"))
                .priceSource(PriceSource.PRICING_SERVICE)
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .build();
        order.getLines().add(line);
        return order;
    }

    @Test
    @DisplayName("CHK-001: happy path → PENDING_PAYMENT, invoice reference stored, balanceDue = grandTotal")
    void checkout_happyPath() {
        SalesOrder order = draftOrderWithLine();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CheckoutResult result = salesOrderService.checkout(ORDER_ID, "chk-key-1");

        assertThat(result.replay()).isFalse();
        assertThat(result.summary().status()).isEqualTo("PENDING_PAYMENT");
        assertThat(result.summary().invoiceId()).isEqualTo(INVOICE_ID.toString());
        assertThat(result.summary().invoiceNumber()).isEqualTo("INV-1");
        assertThat(order.getBalanceDue()).isEqualByComparingTo(order.getGrandTotal());
        assertThat(order.getAmountPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getCheckoutIdempotencyKey()).isEqualTo("chk-key-1");

        ArgumentCaptor<OrderInvoiceCreationRequest> captor = ArgumentCaptor.forClass(OrderInvoiceCreationRequest.class);
        verify(invoicingPort).createInvoiceForOrder(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(ORDER_ID);
        assertThat(captor.getValue().getLines()).hasSize(1);
        verify(orderTaxService).recomputeTax(order);
    }

    @Test
    @DisplayName("CHK-002: replayed Idempotency-Key on the same order → same result, no second invoice call")
    void checkout_replay_returnsExisting() {
        SalesOrder order = draftOrderWithLine();
        order.setStatus(SalesOrderStatus.PENDING_PAYMENT);
        order.setCheckoutIdempotencyKey("chk-key-1");
        order.setInvoiceId(INVOICE_ID);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CheckoutResult result = salesOrderService.checkout(ORDER_ID, "chk-key-1");

        assertThat(result.replay()).isTrue();
        assertThat(result.summary().status()).isEqualTo("PENDING_PAYMENT");
        verify(invoicingPort, never()).createInvoiceForOrder(any());
    }

    @Test
    @DisplayName("CHK-003: key already used for a different order → conflict")
    void checkout_keyUsedByOtherOrder_conflicts() {
        SalesOrder order = draftOrderWithLine();
        SalesOrder other = draftOrderWithLine();
        other.setOrderId(UUID.randomUUID());
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(salesOrderRepository.findByCheckoutIdempotencyKey("chk-key-1")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> salesOrderService.checkout(ORDER_ID, "chk-key-1"))
                .isInstanceOf(CartIdempotencyConflictException.class);
    }

    @Test
    @DisplayName("CHK-004: empty cart → IllegalStateException, no transition")
    void checkout_emptyCart_rejected() {
        SalesOrder order = draftOrderWithLine();
        order.getLines().clear();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> salesOrderService.checkout(ORDER_ID, "chk-key-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.DRAFT);
    }

    @Test
    @DisplayName("CHK-005: PENDING customer validation → hard block (resolved Q8)")
    void checkout_pendingCustomerValidation_blocked() {
        SalesOrder order = draftOrderWithLine();
        order.setCustomerValidationStatus(CustomerValidationStatus.PENDING);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> salesOrderService.checkout(ORDER_ID, "chk-key-1"))
                .isInstanceOf(InvalidCustomerException.class);
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.DRAFT);
    }

    @Test
    @DisplayName("CHK-006: insufficient availability is admitted as a backorder, not rejected (CAP #1315)")
    void checkout_insufficientAvailability_admittedAsBackorder() {
        SalesOrder order = draftOrderWithLine();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(inventoryPort.checkAvailability(anyString(), any(), any()))
                .thenReturn(new InventoryResult(false, BigDecimal.valueOf(0)));

        salesOrderService.checkout(ORDER_ID, "chk-key-1");

        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.PENDING_PAYMENT);
        assertThat(order.getLines().getFirst().getFulfillmentStatus()).isEqualTo(FulfillmentStatus.BACKORDER);
    }

    @Test
    @DisplayName("CHK-006b: a covered line checks out labelled AVAILABLE")
    void checkout_sufficientAvailability_labelledAvailable() {
        SalesOrder order = draftOrderWithLine();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        salesOrderService.checkout(ORDER_ID, "chk-key-1");

        assertThat(order.getLines().getFirst().getFulfillmentStatus()).isEqualTo(FulfillmentStatus.AVAILABLE);
    }

    @Test
    @DisplayName("CHK-007: invoice creation failure propagates (transaction rolls the checkout back)")
    void checkout_invoicingFailure_propagates() {
        SalesOrder order = draftOrderWithLine();
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(invoicingPort.createInvoiceForOrder(any()))
                .thenThrow(new InvoicingUnavailableException("pos-invoice down"));

        assertThatThrownBy(() -> salesOrderService.checkout(ORDER_ID, "chk-key-1"))
                .isInstanceOf(InvoicingUnavailableException.class);
    }

    @Test
    @DisplayName("CHK-008: blank Idempotency-Key → IllegalArgumentException (spec R9.3)")
    void checkout_blankKey_rejected() {
        assertThatThrownBy(() -> salesOrderService.checkout(ORDER_ID, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CHK-009: QUOTED order checks out (quote acceptance path)")
    void checkout_fromQuoted_succeeds() {
        SalesOrder order = draftOrderWithLine();
        order.setStatus(SalesOrderStatus.QUOTED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CheckoutResult result = salesOrderService.checkout(ORDER_ID, "chk-key-1");

        assertThat(result.summary().status()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("CHK-010: COMPLETED order cannot re-checkout with a new key")
    void checkout_completedOrder_rejected() {
        SalesOrder order = draftOrderWithLine();
        order.setStatus(SalesOrderStatus.COMPLETED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> salesOrderService.checkout(ORDER_ID, "chk-key-2"))
                .isInstanceOf(com.positivity.order.internal.exception.InvalidOrderStateTransitionException.class);
    }
}
