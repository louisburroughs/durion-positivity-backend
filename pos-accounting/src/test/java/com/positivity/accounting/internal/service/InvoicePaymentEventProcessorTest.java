package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.exception.AccountingEventRejectedException;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvoicePaymentEventProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    private static final UUID PAYMENT_ID = UUID.fromString("0199a000-0000-7000-8000-000000000002");
    private static final UUID INVOICE_ID = UUID.fromString("0199a000-0000-7000-8000-000000000003");
    private static final UUID CUSTOMER_ID = UUID.fromString("0199a000-0000-7000-8000-000000000004");

    @Mock
    private PaymentApplicationService paymentApplicationService;

    @Mock
    private ReceivablePaymentRepository receivablePaymentRepository;

    @Mock
    private InvoiceBalanceCalculator invoiceBalanceCalculator;

    private InvoicePaymentEventProcessor processor;
    private ExtInvoice invoice;

    @BeforeEach
    void setUp() {
        processor = new InvoicePaymentEventProcessor(
                paymentApplicationService,
                receivablePaymentRepository,
                invoiceBalanceCalculator,
                new LedgerCurrency("USD"),
                Clock.fixed(NOW, ZoneOffset.UTC));
        invoice = new ExtInvoice();
        invoice.setInvoiceId(INVOICE_ID);
        invoice.setPartyId(CUSTOMER_ID.toString());
        invoice.setStatus("FINALIZED");
    }

    @Test
    @DisplayName("records the payment and applies it to the invoice; the event is PROCESSED with no JE of its own")
    void recordsAndAppliesPayment() {
        AccountingEvent event = event(validPayload());
        stubEligibleInvoice(new BigDecimal("150.00"));
        when(receivablePaymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

        processor.process(event);

        verify(paymentApplicationService)
                .handlePaymentCleared(
                        PAYMENT_ID,
                        CUSTOMER_ID,
                        "USD",
                        new BigDecimal("100.00"),
                        Instant.parse("2026-10-02T15:30:00Z"),
                        EVENT_ID);
        ArgumentCaptor<PaymentApplicationRequest> request = ArgumentCaptor.forClass(PaymentApplicationRequest.class);
        verify(paymentApplicationService).applyPaymentToInvoices(eq(PAYMENT_ID), request.capture());
        assertThat(request.getValue().getApplicationRequestId()).isEqualTo("INVOICE_PAYMENT:" + EVENT_ID);
        assertThat(request.getValue().getApplications()).singleElement().satisfies(app -> {
            assertThat(app.getInvoiceId()).isEqualTo(INVOICE_ID);
            assertThat(app.getAmountToApply()).isEqualByComparingTo("100.00");
        });
        verify(paymentApplicationService, never()).creditUnappliedPayment(any(), anyString());

        assertThat(event.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(event.getIdempotencyOutcome()).isEqualTo("NEW");
        assertThat(event.getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(event.getDomainKeyId()).isEqualTo(PAYMENT_ID.toString());
        assertThat(event.getProcessedAt()).isEqualTo(NOW);
        assertThat(event.getJournalEntryId()).isNull();
    }

    @Test
    @DisplayName("a payment for an invoice already paid in full becomes a CustomerCredit")
    void paidInFullInvoice_creditsWholePayment() {
        AccountingEvent event = event(validPayload());
        stubEligibleInvoice(BigDecimal.ZERO);
        when(receivablePaymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

        processor.process(event);

        verify(paymentApplicationService).creditUnappliedPayment(PAYMENT_ID, "INVOICE_PAYMENT:" + EVENT_ID);
        verify(paymentApplicationService, never()).applyPaymentToInvoices(any(), any());
        assertThat(event.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
    }

    @Test
    @DisplayName("a paymentId already recorded by another path is not recorded again")
    void paymentRecordedElsewhere_duplicateIgnored() {
        AccountingEvent event = event(validPayload());
        stubEligibleInvoiceNoBalance();
        when(receivablePaymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(recorded(new BigDecimal("100.00"), UUID.randomUUID())));

        processor.process(event);

        verify(paymentApplicationService, never()).handlePaymentCleared(any(), any(), any(), any(), any(), any());
        verify(paymentApplicationService, never()).applyPaymentToInvoices(any(), any());
        assertThat(event.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(event.getIdempotencyOutcome()).isEqualTo("DUPLICATE_IGNORED");
    }

    @Test
    @DisplayName("a replay of the same event re-runs the idempotent application instead of recording twice")
    void sameEventReplay_reappliesIdempotently() {
        AccountingEvent event = event(validPayload());
        stubEligibleInvoice(new BigDecimal("100.00"));
        when(receivablePaymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(recorded(new BigDecimal("100.00"), EVENT_ID)));

        processor.process(event);

        verify(paymentApplicationService, never()).handlePaymentCleared(any(), any(), any(), any(), any(), any());
        verify(paymentApplicationService).applyPaymentToInvoices(eq(PAYMENT_ID), any());
        assertThat(event.getIdempotencyOutcome()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("the same paymentId with a different amount is a DUPLICATE_CONFLICT")
    void paymentRecordedWithOtherAmount_conflict() {
        AccountingEvent event = event(validPayload());
        stubEligibleInvoiceNoBalance();
        when(receivablePaymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(recorded(new BigDecimal("99.00"), UUID.randomUUID())));

        assertRejected(event, AccountingEventStatus.FAILED, InvoicePaymentEventProcessor.DUPLICATE_CONFLICT);
    }

    @Test
    @DisplayName("a payload without paymentId (the legacy SDK shape) fails INVALID_PAYLOAD")
    void missingPaymentId_invalidPayload() {
        Map<String, Object> payload = validPayload();
        payload.remove("paymentId");

        assertRejected(event(payload), AccountingEventStatus.FAILED, InvoicePaymentEventProcessor.INVALID_PAYLOAD);
        verify(paymentApplicationService, never()).handlePaymentCleared(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a non-positive amount fails INVALID_PAYLOAD")
    void zeroAmount_invalidPayload() {
        Map<String, Object> payload = validPayload();
        payload.put("amountPaid", 0);

        assertRejected(event(payload), AccountingEventStatus.FAILED, InvoicePaymentEventProcessor.INVALID_PAYLOAD);
    }

    @Test
    @DisplayName("a foreign currency suspends CURRENCY_NOT_SUPPORTED before anything is read or written")
    void foreignCurrency_suspended() {
        Map<String, Object> payload = validPayload();
        payload.put("currency", "EUR");

        assertRejected(event(payload), AccountingEventStatus.SUSPENDED, "CURRENCY_NOT_SUPPORTED");
        verify(invoiceBalanceCalculator, never()).findInvoice(any());
    }

    @Test
    @DisplayName("an invoice not yet in the replica suspends INVOICE_NOT_FOUND (retryable)")
    void invoiceMissing_suspended() {
        when(invoiceBalanceCalculator.findInvoice(INVOICE_ID)).thenReturn(Optional.empty());

        assertRejected(
                event(validPayload()), AccountingEventStatus.SUSPENDED, InvoicePaymentEventProcessor.INVOICE_NOT_FOUND);
    }

    @Test
    @DisplayName("a voided invoice fails INVOICE_NOT_ELIGIBLE")
    void voidedInvoice_failed() {
        invoice.setStatus("VOIDED");
        when(invoiceBalanceCalculator.findInvoice(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(invoiceBalanceCalculator.isArEligible(invoice)).thenReturn(false);

        assertRejected(
                event(validPayload()), AccountingEventStatus.FAILED, InvoicePaymentEventProcessor.INVOICE_NOT_ELIGIBLE);
    }

    @Test
    @DisplayName("a customerId that disagrees with the invoice's customer fails INVALID_PAYLOAD")
    void customerMismatch_failed() {
        Map<String, Object> payload = validPayload();
        payload.put("customerId", UUID.randomUUID().toString());
        when(invoiceBalanceCalculator.findInvoice(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(invoiceBalanceCalculator.isArEligible(invoice)).thenReturn(true);

        assertRejected(event(payload), AccountingEventStatus.FAILED, InvoicePaymentEventProcessor.INVALID_PAYLOAD);
    }

    private void assertRejected(AccountingEvent event, AccountingEventStatus status, String reasonCode) {
        assertThatThrownBy(() -> processor.process(event))
                .isInstanceOfSatisfying(AccountingEventRejectedException.class, rejected -> {
                    assertThat(rejected.getStatus()).isEqualTo(status);
                    assertThat(rejected.getReasonCode()).isEqualTo(reasonCode);
                });
        assertThat(event.getStatus()).isEqualTo(AccountingEventStatus.RECEIVED);
    }

    private void stubEligibleInvoice(BigDecimal balanceDue) {
        stubEligibleInvoiceNoBalance();
        when(invoiceBalanceCalculator.balanceDue(invoice)).thenReturn(balanceDue);
    }

    private void stubEligibleInvoiceNoBalance() {
        when(invoiceBalanceCalculator.findInvoice(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(invoiceBalanceCalculator.isArEligible(invoice)).thenReturn(true);
    }

    private static ReceivablePayment recorded(BigDecimal amount, UUID sourceEventId) {
        ReceivablePayment payment = new ReceivablePayment();
        payment.setPaymentId(PAYMENT_ID);
        payment.setCustomerId(CUSTOMER_ID);
        payment.setCurrency("USD");
        payment.setTotalAmount(amount);
        payment.setUnappliedAmount(amount);
        payment.setSourceEventId(sourceEventId);
        return payment;
    }

    private static Map<String, Object> validPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentId", PAYMENT_ID.toString());
        payload.put("invoiceId", INVOICE_ID.toString());
        payload.put("amountPaid", 100.00);
        payload.put("currency", "usd");
        payload.put("paidAt", "2026-10-02T15:30:00Z");
        payload.put("paymentMethod", "CREDIT_CARD");
        return payload;
    }

    private static AccountingEvent event(Map<String, Object> payload) {
        AccountingEvent event = new AccountingEvent();
        event.setEventId(EVENT_ID);
        event.setEventType(InvoicePaymentEventProcessor.EVENT_TYPE);
        event.setStatus(AccountingEventStatus.RECEIVED);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventType", InvoicePaymentEventProcessor.EVENT_TYPE);
        envelope.put("sourceSystem", "SDK_ITEST");
        envelope.put("payload", payload);
        event.setPayload(envelope);
        return event;
    }
}
