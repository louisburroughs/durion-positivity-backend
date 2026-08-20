package com.positivity.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.CustomerValidationStatus;
import com.positivity.order.internal.entity.ExtBillingRules;
import com.positivity.order.internal.entity.ExtCustomer;
import com.positivity.order.internal.entity.ExtProduct;
import com.positivity.order.internal.entity.FulfillmentStatus;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.PriceSource;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.InvalidCustomerException;
import com.positivity.order.internal.exception.InvalidOrderStateTransitionException;
import com.positivity.order.internal.exception.OrderVoidBlockedException;
import com.positivity.order.internal.repository.ExtBillingRulesRepository;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.ExtProductRepository;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.OrderStatusHistoryRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.OrderStateMachine;
import com.positivity.order.internal.service.OrderTotalsCalculator;
import com.positivity.order.internal.service.SalesOrderServiceImpl;
import com.positivity.order.service.model.CheckoutResult;
import com.positivity.order.service.model.SalesOrderSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for Wave 4 order flows: void (story C5, #1076), ON_ACCOUNT tender (story C4, #1075),
 * and checkout serial enforcement (story H3, #1080).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SalesOrderServiceImpl — Wave 4 (void / on-account / serials)")
class SalesOrderWave4Test {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000044");

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
    private ExtProductRepository extProductRepository;

    @Mock
    private ExtCustomerRepository extCustomerRepository;

    @Mock
    private ExtBillingRulesRepository extBillingRulesRepository;

    @Mock
    private OrderPaymentRecordRepository orderPaymentRecordRepository;

    @Mock
    private com.positivity.order.internal.repository.RegisterSessionRepository registerSessionRepository;

    @Mock
    private OrderDomainEventPublisher orderDomainEventPublisher;

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
        when(salesOrderRepository.findByCheckoutIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(orderPaymentRecordRepository.findByOrderId(any())).thenReturn(List.of());
        when(orderPaymentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryPort.checkAvailability(anyString(), any(), any()))
                .thenReturn(new InventoryResult(true, BigDecimal.valueOf(999)));
        when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue(anyString()))
                .thenReturn(Optional.empty());
        when(pricingPort.quoteForSku(anyString(), anyInt(), any(), any()))
                .thenReturn(new PricingQuote(
                        PricingQuote.Status.PRICED, money("50.00"), UUID.randomUUID(), "Widget", null, null));
        when(invoicingPort.createInvoiceForOrder(any()))
                .thenReturn(new InvoiceRef(INVOICE_ID, "INV-1", "DRAFT", money("100.00"), false));
        SecurityContextHolder.clearContext();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static void grantAuthorities(String... authorities) {
        List<SimpleGrantedAuthority> granted =
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("clerk-1", "n/a", granted));
    }

    private SalesOrder orderWithLine(SalesOrderStatus status) {
        SalesOrder order = SalesOrder.builder()
                .orderId(ORDER_ID)
                .clerkId("clerk-1")
                .terminalId("terminal-1")
                .status(status)
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

    // ── C5: void ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("VOID-001: PENDING_PAYMENT order with no settled payments → VOIDED, invoice cancelled")
    void voidOrder_happyPath() {
        SalesOrder order = orderWithLine(SalesOrderStatus.PENDING_PAYMENT);
        order.setInvoiceId(INVOICE_ID);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        SalesOrderSummary result = salesOrderService.voidOrder(ORDER_ID, "walked away");

        assertThat(result.status()).isEqualTo("VOIDED");
        verify(invoicingPort).cancelInvoice(INVOICE_ID);
    }

    @Test
    @DisplayName("VOID-002: settled payment on the ledger → 409 ORDER_VOID_BLOCKED pointing at cancel")
    void voidOrder_settledPayment_blocked() {
        SalesOrder order = orderWithLine(SalesOrderStatus.PENDING_PAYMENT);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderPaymentRecordRepository.findByOrderId(ORDER_ID))
                .thenReturn(List.of(OrderPaymentRecord.builder()
                        .orderId(ORDER_ID)
                        .recordType(OrderPaymentRecord.RecordType.SETTLED)
                        .methodType("CARD")
                        .amount(money("40.00"))
                        .occurredAt(Instant.now())
                        .build()));

        assertThatThrownBy(() -> salesOrderService.voidOrder(ORDER_ID, null))
                .isInstanceOf(OrderVoidBlockedException.class);
        verify(invoicingPort, never()).cancelInvoice(any());
    }

    @Test
    @DisplayName("VOID-003: DRAFT order cannot be voided (drafts are cancelled, not voided)")
    void voidOrder_fromDraft_rejected() {
        SalesOrder order = orderWithLine(SalesOrderStatus.DRAFT);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> salesOrderService.voidOrder(ORDER_ID, null))
                .isInstanceOf(InvalidOrderStateTransitionException.class);
    }

    @Test
    @DisplayName("VOID-004: voiding a VOIDED order is an idempotent no-op")
    void voidOrder_alreadyVoided_idempotent() {
        SalesOrder order = orderWithLine(SalesOrderStatus.VOIDED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThat(salesOrderService.voidOrder(ORDER_ID, null).status()).isEqualTo("VOIDED");
        verify(invoicingPort, never()).cancelInvoice(any());
    }

    // ── C4: ON_ACCOUNT tender ────────────────────────────────────────────────

    private SalesOrder validatedCommercialOrder() {
        SalesOrder order = orderWithLine(SalesOrderStatus.DRAFT);
        order.setCustomerId(CUSTOMER_ID);
        order.setCustomerValidationStatus(CustomerValidationStatus.VALIDATED);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(extCustomerRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(ExtCustomer.builder()
                        .partyId(CUSTOMER_ID)
                        .status("ACTIVE")
                        .partyType("COMMERCIAL")
                        .requirementsMet(true)
                        .aggregateVersion(1)
                        .syncedAt(Instant.now())
                        .build()));
        when(extBillingRulesRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(ExtBillingRules.builder()
                        .partyId(CUSTOMER_ID)
                        .paymentTerms("NET30")
                        .creditHold(false)
                        .aggregateVersion(1)
                        .syncedAt(Instant.now())
                        .build()));
        return order;
    }

    @Test
    @DisplayName("ONACC-001: validated commercial customer with terms → order completes with AR tender")
    void onAccount_happyPath_completesOrder() {
        SalesOrder order = validatedCommercialOrder();
        grantAuthorities("order:order:charge_on_account");

        CheckoutResult result = salesOrderService.checkout(ORDER_ID, "chk-1", "ON_ACCOUNT");

        assertThat(result.summary().status()).isEqualTo("COMPLETED");
        assertThat(order.getAmountPaid()).isEqualByComparingTo(order.getGrandTotal());
        assertThat(order.getBalanceDue()).isEqualByComparingTo("0");
        verify(orderDomainEventPublisher).publishOrderCompleted(eq(order), anyList());
        verify(orderPaymentRecordRepository)
                .save(org.mockito.ArgumentMatchers.argThat(record -> "ON_ACCOUNT".equals(record.getMethodType())
                        && record.getRecordType() == OrderPaymentRecord.RecordType.SETTLED));
    }

    @Test
    @DisplayName("ONACC-002: missing charge-on-account permission → 403")
    void onAccount_missingPermission_denied() {
        validatedCommercialOrder();
        grantAuthorities("order:order:checkout");

        assertThatThrownBy(() -> salesOrderService.checkout(ORDER_ID, "chk-1", "ON_ACCOUNT"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("ONACC-003: anonymous order → 422 invalid customer")
    void onAccount_anonymous_rejected() {
        SalesOrder order = orderWithLine(SalesOrderStatus.DRAFT);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        grantAuthorities("order:order:charge_on_account");

        assertThatThrownBy(() -> salesOrderService.checkout(ORDER_ID, "chk-1", "ON_ACCOUNT"))
                .isInstanceOf(InvalidCustomerException.class);
    }

    @Test
    @DisplayName("ONACC-004: person (non-commercial) customer → 422")
    void onAccount_personParty_rejected() {
        SalesOrder order = validatedCommercialOrder();
        when(extCustomerRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(ExtCustomer.builder()
                        .partyId(CUSTOMER_ID)
                        .status("ACTIVE")
                        .partyType("PERSON")
                        .requirementsMet(true)
                        .aggregateVersion(1)
                        .syncedAt(Instant.now())
                        .build()));
        grantAuthorities("order:order:charge_on_account");

        assertThatThrownBy(() -> salesOrderService.checkout(ORDER_ID, "chk-1", "ON_ACCOUNT"))
                .isInstanceOf(InvalidCustomerException.class);
    }

    @Test
    @DisplayName("ONACC-005: no billing terms replica row → 422")
    void onAccount_noBillingTerms_rejected() {
        validatedCommercialOrder();
        when(extBillingRulesRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
        grantAuthorities("order:order:charge_on_account");

        assertThatThrownBy(() -> salesOrderService.checkout(ORDER_ID, "chk-1", "ON_ACCOUNT"))
                .isInstanceOf(InvalidCustomerException.class);
    }

    @Test
    @DisplayName("ONACC-006: credit hold → 422")
    void onAccount_creditHold_rejected() {
        validatedCommercialOrder();
        when(extBillingRulesRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(ExtBillingRules.builder()
                        .partyId(CUSTOMER_ID)
                        .paymentTerms("NET30")
                        .creditHold(true)
                        .aggregateVersion(1)
                        .syncedAt(Instant.now())
                        .build()));
        grantAuthorities("order:order:charge_on_account");

        assertThatThrownBy(() -> salesOrderService.checkout(ORDER_ID, "chk-1", "ON_ACCOUNT"))
                .isInstanceOf(InvalidCustomerException.class);
    }

    // ── E2: settlement-path reconciliation (gate V4, spec R7.2) ──────────────

    @Test
    @DisplayName("E2-001: workorder-linked checkout passes workorderId and adopts the existing workorder invoice")
    void checkout_workorderLinked_adoptsExistingInvoice() {
        SalesOrder order = orderWithLine(SalesOrderStatus.DRAFT);
        UUID workorderId = UUID.randomUUID();
        order.setWorkOrderId(workorderId);
        order.getLines().getFirst().setSourceType(com.positivity.order.internal.entity.SourceType.WORKORDER);
        order.getLines().getFirst().setSourceId(workorderId.toString());
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        UUID existingInvoiceId = UUID.randomUUID();
        when(invoicingPort.createInvoiceForOrder(any()))
                .thenReturn(new InvoiceRef(existingInvoiceId, "INV-WO", "DRAFT", money("100.00"), true));

        CheckoutResult result = salesOrderService.checkout(ORDER_ID, "chk-1");

        org.mockito.ArgumentCaptor<com.positivity.shared.dto.OrderInvoiceCreationRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.positivity.shared.dto.OrderInvoiceCreationRequest.class);
        verify(invoicingPort).createInvoiceForOrder(captor.capture());
        // Spec R7.2: the workorder reference rides the request so pos-invoice dedupes instead of
        // double-invoicing; the returned (existing) invoice becomes the tender target.
        assertThat(captor.getValue().getWorkorderId()).isEqualTo(workorderId);
        assertThat(result.summary().invoiceId()).isEqualTo(existingInvoiceId.toString());
        assertThat(result.summary().invoiceNumber()).isEqualTo("INV-WO");
        assertThat(result.summary().status()).isEqualTo("PENDING_PAYMENT");
    }

    // ── H3: serial enforcement at checkout ───────────────────────────────────

    @Test
    @DisplayName("SER-001: SERIAL-tracked product without serials → 422 at checkout")
    void checkout_serialTrackedWithoutSerials_rejected() {
        SalesOrder order = orderWithLine(SalesOrderStatus.DRAFT);
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue("SKU-1"))
                .thenReturn(Optional.of(ExtProduct.builder()
                        .productId(UUID.randomUUID())
                        .sku("SKU-1")
                        .active(true)
                        .trackingLevel("SERIAL")
                        .aggregateVersion(1)
                        .syncedAt(Instant.now())
                        .build()));

        assertThatThrownBy(() -> salesOrderService.checkout(ORDER_ID, "chk-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serial");
    }

    @Test
    @DisplayName("SER-002: SERIAL-tracked product with one serial per unit checks out")
    void checkout_serialTrackedWithSerials_succeeds() {
        SalesOrder order = orderWithLine(SalesOrderStatus.DRAFT);
        order.getLines().getFirst().setSerialNumbers(new ArrayList<>(List.of("SN-1", "SN-2")));
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue("SKU-1"))
                .thenReturn(Optional.of(ExtProduct.builder()
                        .productId(UUID.randomUUID())
                        .sku("SKU-1")
                        .active(true)
                        .trackingLevel("SERIAL")
                        .aggregateVersion(1)
                        .syncedAt(Instant.now())
                        .build()));

        CheckoutResult result = salesOrderService.checkout(ORDER_ID, "chk-1");

        assertThat(result.summary().status()).isEqualTo("PENDING_PAYMENT");
        assertThat(result.summary().lines().getFirst().serialNumbers()).containsExactly("SN-1", "SN-2");
    }
}
