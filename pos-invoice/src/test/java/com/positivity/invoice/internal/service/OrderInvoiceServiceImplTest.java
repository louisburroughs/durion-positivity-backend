package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.config.InvoiceEventPublisher;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.exception.InvalidInvoiceStateException;
import com.positivity.invoice.internal.exception.InvoiceRequestValidationException;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.service.model.CreateDepositCommand;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import com.positivity.shared.dto.OrderInvoiceLineItem;
import com.positivity.shared.dto.OrderInvoiceResponse;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for the from-order invoice creation path (order parity story C2, #1070): assembly
 * from the order's authoritative figures, orderId idempotency, and workorder dedupe (spec R7.2).
 *
 * <p>Not exercised here: the {@code invoice.getId() == null} fallback branch inside {@code
 * generateInvoiceNumber} (picks a fresh UUID v7 instead of the persisted id). It guards against a
 * save() that returns without an id, which the UUID v7 id-generation strategy never does in
 * practice — reaching it would require faking a broken repository, not a real scenario.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderInvoiceServiceImpl — parity story C2")
class OrderInvoiceServiceImplTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private InvoiceEventPublisher invoiceEventPublisher;

    @Mock
    private com.positivity.invoice.internal.repository.PaymentIntentRepository paymentIntentRepository;

    @Mock
    private com.positivity.invoice.internal.service.DepositCreditService depositCreditService;

    private OrderInvoiceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderInvoiceServiceImpl(
                invoiceRepository,
                paymentIntentRepository,
                invoiceEventPublisher,
                depositCreditService,
                Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC));
        when(invoiceRepository.findByOrderId(any())).thenReturn(Optional.empty());
        when(invoiceRepository.save(any())).thenAnswer(inv -> {
            Invoice invoice = inv.getArgument(0);
            if (invoice.getId() == null) {
                invoice.setId(UUID.randomUUID());
            }
            return invoice;
        });
        when(depositCreditService.applyAvailableCredits(any(), any(), any(), any()))
                .thenReturn(java.math.BigDecimal.ZERO);
    }

    private OrderInvoiceCreationRequest request(UUID workorderId) {
        return OrderInvoiceCreationRequest.builder()
                .orderId(ORDER_ID)
                .workorderId(workorderId)
                .customerId(CUSTOMER_ID)
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.00"))
                .totalAmount(new BigDecimal("108.00"))
                .lines(List.of(OrderInvoiceLineItem.builder()
                        .orderLineId(UUID.randomUUID())
                        .description("Widget")
                        .quantity(new BigDecimal("2"))
                        .unitPrice(new BigDecimal("50.00"))
                        .amount(new BigDecimal("100.00"))
                        .taxAmount(new BigDecimal("8.00"))
                        .type("PART")
                        .build()))
                .build();
    }

    /**
     * A well-formed deposit-take base request (#1629): zero tax, subtotal == totalAmount ==
     * {@code amount}, and a single gross line summing to it. Deposit-take tests that don't care
     * about the tax figures themselves build on this rather than {@link #request} (which carries
     * a nonzero tax appropriate for an ordinary sale) so they don't trip the #1629 zero-tax guard.
     */
    private OrderInvoiceCreationRequest depositTakeRequest(UUID workorderId, BigDecimal amount) {
        return OrderInvoiceCreationRequest.builder()
                .orderId(ORDER_ID)
                .workorderId(workorderId)
                .customerId(CUSTOMER_ID)
                .subtotal(amount)
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(amount)
                .lines(List.of(OrderInvoiceLineItem.builder()
                        .orderLineId(UUID.randomUUID())
                        .description("Widget")
                        .quantity(new BigDecimal("2"))
                        .unitPrice(amount.divide(new BigDecimal("2")))
                        .amount(amount)
                        .taxAmount(BigDecimal.ZERO)
                        .type("PART")
                        .build()))
                .build();
    }

    @Test
    @DisplayName("OIS-E4a: a deposit-take order registers a deposit credit against its source")
    void depositTake_registersCredit() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        OrderInvoiceCreationRequest depositTake = depositTakeRequest(null, new BigDecimal("108.00"));
        depositTake.setDepositSourceType("WORKORDER");
        depositTake.setDepositSourceId(workorderId);
        depositTake.setDepositAmount(new BigDecimal("108.00"));

        service.createInvoiceForOrder(depositTake);

        org.mockito.ArgumentCaptor<com.positivity.invoice.internal.service.model.CreateDepositCommand> cmd =
                org.mockito.ArgumentCaptor.forClass(
                        com.positivity.invoice.internal.service.model.CreateDepositCommand.class);
        verify(depositCreditService).createDeposit(cmd.capture());
        assertThat(cmd.getValue().orderId()).isEqualTo(ORDER_ID);
        assertThat(cmd.getValue().sourceType()).isEqualTo("WORKORDER");
        assertThat(cmd.getValue().sourceId()).isEqualTo(workorderId);
        assertThat(cmd.getValue().amount()).isEqualByComparingTo("108.00");
    }

    @Test
    @DisplayName("OIS-E4b: a workorder settlement applies available deposit credits and reports the amount")
    void settlement_appliesDepositCredits() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
        when(depositCreditService.applyAvailableCredits(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("50.00"));

        OrderInvoiceResponse response = service.createInvoiceForOrder(request(workorderId));

        assertThat(response.getDepositApplied()).isEqualByComparingTo("50.00");
        verify(depositCreditService).applyAvailableCredits(any(), any(), any(), any());
    }

    @Test
    @DisplayName("OIS-E4c: a zero or negative depositAmount is not treated as a deposit take")
    void depositAmountZero_doesNotRegisterCredit() {
        OrderInvoiceCreationRequest zeroDeposit = request(null);
        zeroDeposit.setDepositAmount(BigDecimal.ZERO);

        service.createInvoiceForOrder(zeroDeposit);

        verify(depositCreditService, never()).createDeposit(any());
    }

    @Test
    @DisplayName("OIS-E4d: a deposit amount without a source type is rejected before any deposit is recorded")
    void depositAmountWithoutSourceType_rejected() {
        OrderInvoiceCreationRequest depositTake = depositTakeRequest(null, new BigDecimal("50.00"));
        depositTake.setDepositAmount(new BigDecimal("50.00"));
        depositTake.setDepositSourceId(UUID.randomUUID());

        assertThatThrownBy(() -> service.createInvoiceForOrder(depositTake))
                .isInstanceOf(InvoiceRequestValidationException.class)
                .hasMessageContaining("depositSourceType");
    }

    @Test
    @DisplayName("OIS-E4e: a deposit amount without a source id is rejected before any deposit is recorded")
    void depositAmountWithoutSourceId_rejected() {
        OrderInvoiceCreationRequest depositTake = depositTakeRequest(null, new BigDecimal("50.00"));
        depositTake.setDepositAmount(new BigDecimal("50.00"));
        depositTake.setDepositSourceType("WORKORDER");

        assertThatThrownBy(() -> service.createInvoiceForOrder(depositTake))
                .isInstanceOf(InvoiceRequestValidationException.class)
                .hasMessageContaining("depositSourceId");
    }

    @Test
    @DisplayName("OIS-E4f: a deposit taken on an anonymous counter sale carries no party id on the credit")
    void depositTake_anonymousCustomer_omitsCustomerId() {
        OrderInvoiceCreationRequest anonymousDeposit = depositTakeRequest(null, new BigDecimal("50.00"));
        anonymousDeposit.setCustomerId(null);
        anonymousDeposit.setDepositAmount(new BigDecimal("50.00"));
        anonymousDeposit.setDepositSourceType("ORDER");
        anonymousDeposit.setDepositSourceId(ORDER_ID);

        service.createInvoiceForOrder(anonymousDeposit);

        ArgumentCaptor<CreateDepositCommand> cmd = ArgumentCaptor.forClass(CreateDepositCommand.class);
        verify(depositCreditService).createDeposit(cmd.capture());
        assertThat(cmd.getValue().partyId()).isNull();
    }

    @Test
    @DisplayName("OIS-1623a: a deposit-take order stamps deposit provenance on the invoice document")
    void depositTake_stampsProvenanceOnInvoice() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
        OrderInvoiceCreationRequest depositTake = depositTakeRequest(null, new BigDecimal("108.00"));
        depositTake.setDepositSourceType("WORKORDER");
        depositTake.setDepositSourceId(workorderId);
        depositTake.setDepositAmount(new BigDecimal("108.00"));

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        service.createInvoiceForOrder(depositTake);

        verify(invoiceRepository, times(2)).save(captor.capture());
        Invoice saved = captor.getAllValues().get(0);
        assertThat(saved.getDepositSourceType())
                .isEqualTo(com.positivity.invoice.internal.enums.DepositSourceType.WORKORDER);
        assertThat(saved.getDepositSourceId()).isEqualTo(workorderId);
    }

    @Test
    @DisplayName("OIS-1623b: an ordinary from-order invoice carries no deposit provenance")
    void ordinaryInvoice_noDepositProvenance() {
        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        service.createInvoiceForOrder(request(null));

        verify(invoiceRepository, times(2)).save(captor.capture());
        Invoice saved = captor.getAllValues().get(0);
        assertThat(saved.getDepositSourceType()).isNull();
        assertThat(saved.getDepositSourceId()).isNull();
    }

    @Test
    @DisplayName("OIS-1629a: a deposit-take request carrying nonzero tax is rejected before any document is created")
    void depositTake_nonzeroTaxRequest_rejected() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-0000000000e4");
        OrderInvoiceCreationRequest depositTake = request(null);
        depositTake.setDepositSourceType("WORKORDER");
        depositTake.setDepositSourceId(workorderId);
        depositTake.setDepositAmount(new BigDecimal("108.00"));
        // Request carries a nonzero tax (upstream caller malformed) — pos-invoice must reject it
        // outright (#1629) rather than silently zeroing tax while desyncing the header from the
        // (still nonzero-net) line items.
        assertThat(depositTake.getTaxAmount()).isEqualByComparingTo("8.00");

        assertThatThrownBy(() -> service.createInvoiceForOrder(depositTake))
                .isInstanceOf(InvoiceRequestValidationException.class)
                .hasMessageContaining("deposit-take invoice requests must carry zero tax");
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "OIS-1629b: a well-formed (zero-tax, gross-line) deposit-take request yields a header-line-consistent invoice")
    void depositTake_wellFormedRequest_yieldsGrossZeroTaxInvoice() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
        OrderInvoiceCreationRequest depositTake = OrderInvoiceCreationRequest.builder()
                .orderId(ORDER_ID)
                .workorderId(workorderId)
                .customerId(CUSTOMER_ID)
                .subtotal(new BigDecimal("108.00"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("108.00"))
                .depositSourceType("WORKORDER")
                .depositSourceId(workorderId)
                .depositAmount(new BigDecimal("108.00"))
                .lines(List.of(OrderInvoiceLineItem.builder()
                        .orderLineId(UUID.randomUUID())
                        .description("Widget")
                        .quantity(new BigDecimal("2"))
                        .unitPrice(new BigDecimal("54.00"))
                        .amount(new BigDecimal("108.00"))
                        .taxAmount(BigDecimal.ZERO)
                        .type("PART")
                        .build()))
                .build();

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        service.createInvoiceForOrder(depositTake);

        verify(invoiceRepository, times(2)).save(captor.capture());
        Invoice saved = captor.getAllValues().get(0);
        assertThat(saved.getTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getSubtotal()).isEqualByComparingTo(saved.getTotal());
        BigDecimal itemLineTotalSum = saved.getItems().stream()
                .map(com.positivity.invoice.internal.entity.InvoiceItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(itemLineTotalSum).isEqualByComparingTo(saved.getSubtotal());
    }

    @Test
    @DisplayName("OIS-1629c: an ordinary from-order invoice keeps the request's tax")
    void ordinaryInvoice_keepsTax() {
        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        service.createInvoiceForOrder(request(null));

        verify(invoiceRepository, times(2)).save(captor.capture());
        Invoice saved = captor.getAllValues().get(0);
        assertThat(saved.getTax()).isEqualByComparingTo("8.00");
        assertThat(saved.getSubtotal()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("OIS-1623c: an unknown deposit source type is rejected before any document is created")
    void depositTake_unknownSourceType_rejected() {
        OrderInvoiceCreationRequest depositTake = request(null);
        depositTake.setDepositSourceType("INVOICE");
        depositTake.setDepositSourceId(UUID.randomUUID());
        depositTake.setDepositAmount(new BigDecimal("50.00"));

        assertThatThrownBy(() -> service.createInvoiceForOrder(depositTake))
                .isInstanceOf(InvoiceRequestValidationException.class)
                .hasMessageContaining("Unknown deposit source type");
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("OIS-001: creates a DRAFT invoice carrying the order's authoritative figures")
    void createsInvoiceFromOrder() {
        OrderInvoiceResponse response = service.createInvoiceForOrder(request(null));

        assertThat(response.isExisting()).isFalse();
        assertThat(response.getInvoiceId()).isNotNull();
        assertThat(response.getInvoiceNumber()).startsWith("INV-");
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.DRAFT.name());
        assertThat(response.getSubtotal()).isEqualByComparingTo("100.00");
        assertThat(response.getTaxAmount()).isEqualByComparingTo("8.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("108.00");
        verify(invoiceEventPublisher).publishInvoiceUpdated(any(Invoice.class));
    }

    @Test
    @DisplayName("OIS-002: anonymous cash sale (no customerId) still produces a receiptable invoice")
    void anonymousSale_createsInvoice() {
        OrderInvoiceCreationRequest anonymous = request(null);
        anonymous.setCustomerId(null);

        OrderInvoiceResponse response = service.createInvoiceForOrder(anonymous);

        assertThat(response.isExisting()).isFalse();
        assertThat(response.getInvoiceId()).isNotNull();
    }

    @Test
    @DisplayName("OIS-003: orderId replay returns the existing invoice, no second create")
    void orderIdReplay_returnsExisting() {
        Invoice existing = new Invoice();
        existing.setId(UUID.randomUUID());
        existing.setOrderId(ORDER_ID);
        existing.setInvoiceNumber("INV-EXISTING");
        existing.setStatus(InvoiceStatus.DRAFT);
        when(invoiceRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));

        OrderInvoiceResponse response = service.createInvoiceForOrder(request(null));

        assertThat(response.isExisting()).isTrue();
        assertThat(response.getInvoiceId()).isEqualTo(existing.getId());
        verify(invoiceRepository, never()).save(any());
        verify(invoiceEventPublisher, never()).publishInvoiceUpdated(any());
    }

    @Test
    @DisplayName("OIS-004: workorder dedupe returns the workorder's invoice and stamps orderId (spec R7.2)")
    void workorderDedupe_returnsWorkorderInvoice() {
        Invoice workorderInvoice = new Invoice();
        workorderInvoice.setId(UUID.randomUUID());
        workorderInvoice.setWorkorderId(WORKORDER_ID);
        workorderInvoice.setInvoiceNumber("INV-WO");
        workorderInvoice.setStatus(InvoiceStatus.DRAFT);
        when(invoiceRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.of(workorderInvoice));

        OrderInvoiceResponse response = service.createInvoiceForOrder(request(WORKORDER_ID));

        assertThat(response.isExisting()).isTrue();
        assertThat(response.getInvoiceId()).isEqualTo(workorderInvoice.getId());
        assertThat(workorderInvoice.getOrderId()).isEqualTo(ORDER_ID);
    }

    @Test
    @DisplayName("OIS-004b: workorder dedupe when the invoice already carries the orderId does not resave it")
    void workorderDedupe_orderIdAlreadySet_doesNotResave() {
        Invoice workorderInvoice = new Invoice();
        workorderInvoice.setId(UUID.randomUUID());
        workorderInvoice.setWorkorderId(WORKORDER_ID);
        workorderInvoice.setOrderId(ORDER_ID);
        workorderInvoice.setInvoiceNumber("INV-WO");
        workorderInvoice.setStatus(InvoiceStatus.DRAFT);
        when(invoiceRepository.findByWorkorderId(WORKORDER_ID)).thenReturn(Optional.of(workorderInvoice));

        OrderInvoiceResponse response = service.createInvoiceForOrder(request(WORKORDER_ID));

        assertThat(response.isExisting()).isTrue();
        assertThat(response.getInvoiceId()).isEqualTo(workorderInvoice.getId());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("OIS-021: a null or blank line description falls back to a generic label")
    void lineDescriptionFallback_nullOrBlank_usesGenericLabel() {
        OrderInvoiceCreationRequest request = request(null);
        request.setLines(List.of(
                OrderInvoiceLineItem.builder()
                        .orderLineId(UUID.randomUUID())
                        .description(null)
                        .quantity(new BigDecimal("1"))
                        .unitPrice(new BigDecimal("40.00"))
                        .amount(new BigDecimal("40.00"))
                        .type("PART")
                        .build(),
                OrderInvoiceLineItem.builder()
                        .orderLineId(UUID.randomUUID())
                        .description("   ")
                        .quantity(new BigDecimal("1"))
                        .unitPrice(new BigDecimal("60.00"))
                        .amount(new BigDecimal("60.00"))
                        .type("LABOR")
                        .build()));

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        service.createInvoiceForOrder(request);

        verify(invoiceRepository, times(2)).save(captor.capture());
        Invoice savedWithItems = captor.getAllValues().get(0);
        assertThat(savedWithItems.getItems()).hasSize(2);
        assertThat(savedWithItems.getItems())
                .allSatisfy(item -> assertThat(item.getDescription()).isEqualTo("Order line item"));
    }

    @Test
    @DisplayName("OIS-020: an invoice number already stamped by persistence is never regenerated")
    void invoiceNumberAlreadyPresent_notRegenerated() {
        // doAnswer (not when(...).thenAnswer(...)) — re-stubbing via when() would invoke the
        // setUp() stub's answer for real while recording this call, NPE-ing on its null arg.
        org.mockito.Mockito.doAnswer(inv -> {
                    Invoice invoice = inv.getArgument(0);
                    if (invoice.getId() == null) {
                        invoice.setId(UUID.randomUUID());
                    }
                    invoice.setInvoiceNumber("INV-PRESET-0001");
                    return invoice;
                })
                .when(invoiceRepository)
                .save(any());

        OrderInvoiceResponse response = service.createInvoiceForOrder(request(null));

        assertThat(response.getInvoiceNumber()).isEqualTo("INV-PRESET-0001");
        verify(invoiceRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("OIS-020b: a blank (non-null) invoice number is regenerated, same as a missing one")
    void invoiceNumberBlank_isRegenerated() {
        // doAnswer, not when(...).thenAnswer(...) — re-stubbing via when() would invoke the
        // setUp() stub's answer for real while recording this call, NPE-ing on its null arg.
        org.mockito.Mockito.doAnswer(inv -> {
                    Invoice invoice = inv.getArgument(0);
                    if (invoice.getId() == null) {
                        invoice.setId(UUID.randomUUID());
                    }
                    if (invoice.getInvoiceNumber() == null) {
                        invoice.setInvoiceNumber("   ");
                    }
                    return invoice;
                })
                .when(invoiceRepository)
                .save(any());

        OrderInvoiceResponse response = service.createInvoiceForOrder(request(null));

        assertThat(response.getInvoiceNumber()).isNotBlank();
        assertThat(response.getInvoiceNumber()).startsWith("INV-");
        verify(invoiceRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("OIS-005: missing orderId / empty lines / negative total are rejected")
    void invalidRequests_rejected() {
        OrderInvoiceCreationRequest noOrder = request(null);
        noOrder.setOrderId(null);
        assertThatThrownBy(() -> service.createInvoiceForOrder(noOrder)).isInstanceOf(IllegalArgumentException.class);

        OrderInvoiceCreationRequest noLines = request(null);
        noLines.setLines(List.of());
        assertThatThrownBy(() -> service.createInvoiceForOrder(noLines))
                .isInstanceOf(InvoiceRequestValidationException.class);

        OrderInvoiceCreationRequest negative = request(null);
        negative.setTotalAmount(new BigDecimal("-1.00"));
        assertThatThrownBy(() -> service.createInvoiceForOrder(negative))
                .isInstanceOf(InvoiceRequestValidationException.class);
    }

    @Test
    @DisplayName("OIS-007: cancel — DRAFT with no live payments → CANCELLED; idempotent replay")
    void cancelInvoice_draftNoPayments_cancels() {
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setInvoiceNumber("INV-1");
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentIntentRepository.findByInvoice_Id(invoice.getId())).thenReturn(List.of());

        OrderInvoiceResponse cancelled = service.cancelInvoice(invoice.getId());

        assertThat(cancelled.getStatus()).isEqualTo(InvoiceStatus.CANCELLED.name());
        assertThat(cancelled.isExisting()).isFalse();
        verify(invoiceEventPublisher).publishInvoiceUpdated(any(Invoice.class));

        OrderInvoiceResponse replay = service.cancelInvoice(invoice.getId());
        assertThat(replay.isExisting()).isTrue();
    }

    @Test
    @DisplayName("OIS-008: cancel with a captured payment → 409")
    void cancelInvoice_withCapturedPayment_rejected() {
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setStatus(InvoiceStatus.DRAFT);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        com.positivity.invoice.internal.entity.PaymentIntent intent =
                new com.positivity.invoice.internal.entity.PaymentIntent();
        intent.setStatus(com.positivity.invoice.internal.enums.PaymentIntentStatus.CAPTURED);
        when(paymentIntentRepository.findByInvoice_Id(invoice.getId())).thenReturn(List.of(intent));

        assertThatThrownBy(() -> service.cancelInvoice(invoice.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payments");
    }

    @Test
    @DisplayName("OIS-008b: cancel with an AUTHORIZED (not yet captured) payment is rejected the same way")
    void cancelInvoice_withAuthorizedPayment_rejected() {
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setStatus(InvoiceStatus.DRAFT);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        com.positivity.invoice.internal.entity.PaymentIntent intent =
                new com.positivity.invoice.internal.entity.PaymentIntent();
        intent.setStatus(com.positivity.invoice.internal.enums.PaymentIntentStatus.AUTHORIZED);
        when(paymentIntentRepository.findByInvoice_Id(invoice.getId())).thenReturn(List.of(intent));

        assertThatThrownBy(() -> service.cancelInvoice(invoice.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payments");
    }

    @Test
    @DisplayName("OIS-008c: cancel with a PENDING gateway call in flight is rejected the same way")
    void cancelInvoice_withPendingPayment_rejected() {
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setStatus(InvoiceStatus.DRAFT);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        com.positivity.invoice.internal.entity.PaymentIntent intent =
                new com.positivity.invoice.internal.entity.PaymentIntent();
        intent.setStatus(com.positivity.invoice.internal.enums.PaymentIntentStatus.PENDING);
        when(paymentIntentRepository.findByInvoice_Id(invoice.getId())).thenReturn(List.of(intent));

        assertThatThrownBy(() -> service.cancelInvoice(invoice.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payments");
    }

    @Test
    @DisplayName("OIS-008d: a VOIDED payment intent never moved money, so it does not block cancellation")
    void cancelInvoice_withVoidedPaymentOnly_stillCancels() {
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setInvoiceNumber("INV-3");
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        com.positivity.invoice.internal.entity.PaymentIntent intent =
                new com.positivity.invoice.internal.entity.PaymentIntent();
        intent.setStatus(com.positivity.invoice.internal.enums.PaymentIntentStatus.VOIDED);
        when(paymentIntentRepository.findByInvoice_Id(invoice.getId())).thenReturn(List.of(intent));

        OrderInvoiceResponse response = service.cancelInvoice(invoice.getId());

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.CANCELLED.name());
    }

    @Test
    @DisplayName("OIS-008e: cancel on a non-DRAFT, non-CANCELLED invoice (e.g. FINALIZED) is rejected")
    void cancelInvoice_finalized_throwsInvalidInvoiceStateException() {
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setStatus(InvoiceStatus.FINALIZED);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.cancelInvoice(invoice.getId()))
                .isInstanceOf(InvalidInvoiceStateException.class);

        verifyNoInteractions(paymentIntentRepository);
    }

    @Test
    @DisplayName("OIS-006: internally inconsistent figures are rejected cent-exact")
    void inconsistentTotals_rejected() {
        OrderInvoiceCreationRequest badLineSum = request(null);
        badLineSum.setSubtotal(new BigDecimal("101.00"));
        badLineSum.setTotalAmount(new BigDecimal("109.00"));
        assertThatThrownBy(() -> service.createInvoiceForOrder(badLineSum))
                .isInstanceOf(InvoiceRequestValidationException.class)
                .hasMessageContaining("subtotal");

        OrderInvoiceCreationRequest badTotal = request(null);
        badTotal.setTotalAmount(new BigDecimal("108.01"));
        assertThatThrownBy(() -> service.createInvoiceForOrder(badTotal))
                .isInstanceOf(InvoiceRequestValidationException.class)
                .hasMessageContaining("totalAmount");
    }

    @Test
    @DisplayName("OIS-022: a missing subtotal, taxAmount, or totalAmount is rejected up front")
    void missingRequiredTotals_rejected() {
        OrderInvoiceCreationRequest missingSubtotal = request(null);
        missingSubtotal.setSubtotal(null);
        assertThatThrownBy(() -> service.createInvoiceForOrder(missingSubtotal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");

        OrderInvoiceCreationRequest missingTax = request(null);
        missingTax.setTaxAmount(null);
        assertThatThrownBy(() -> service.createInvoiceForOrder(missingTax))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");

        OrderInvoiceCreationRequest missingTotal = request(null);
        missingTotal.setTotalAmount(null);
        assertThatThrownBy(() -> service.createInvoiceForOrder(missingTotal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("OIS-023: a null lines list is rejected the same as an empty one")
    void nullLines_rejected() {
        OrderInvoiceCreationRequest noLines = request(null);
        noLines.setLines(null);
        assertThatThrownBy(() -> service.createInvoiceForOrder(noLines))
                .isInstanceOf(InvoiceRequestValidationException.class)
                .hasMessageContaining("at least one line");
    }
}
