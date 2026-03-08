package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.positivity.accounting.internal.dto.PaymentOutcomeRequest;
import com.positivity.accounting.internal.dto.PaymentOutcomeResponse;
import com.positivity.accounting.internal.entity.CustomerCredit;
import com.positivity.accounting.internal.entity.InvoiceStatusView;
import com.positivity.accounting.internal.entity.ReconciliationRecord;
import com.positivity.accounting.internal.enums.PaymentOutcomeType;
import com.positivity.accounting.internal.enums.PaymentStatus;
import com.positivity.accounting.internal.event.InvoicePaymentRecorded;
import com.positivity.accounting.internal.event.InvoicePostingFailed;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.InvoiceStatusViewRepository;
import com.positivity.accounting.internal.repository.ReconciliationRecordRepository;
import com.positivity.accounting.internal.service.PaymentOutcomeProcessingServiceImpl;

/**
 * Unit tests for PaymentOutcomeProcessingService.
 *
 * Covers AC1–AC7 from CAP-251 Story #6:
 * update invoice payment status from payment outcomes, including
 * idempotency, minor-unit arithmetic, chargeback reversal, overpayment credit,
 * and posting-failure escalation.
 *
 * Issue: #6
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentOutcomeProcessingService Unit Tests — CAP-251 #6")
class PaymentOutcomeProcessingServiceTest {

        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Spy
        private Clock clock = TEST_CLOCK;

        @Mock
        private InvoiceStatusViewRepository statusViewRepository;

        @Mock
        private CustomerCreditRepository customerCreditRepository;

        @Mock
        private ReconciliationRecordRepository reconciliationRecordRepository;

        @Mock
        private OutboxService outboxService;

        @Mock
        private ApplicationEventPublisher eventPublisher;

        @InjectMocks
        private PaymentOutcomeProcessingServiceImpl service;

        @Captor
        private ArgumentCaptor<InvoiceStatusView> statusViewCaptor;

        @Captor
        private ArgumentCaptor<CustomerCredit> customerCreditCaptor;

        @Captor
        private ArgumentCaptor<ReconciliationRecord> reconciliationRecordCaptor;

        private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
        private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

        private InvoiceStatusView existingStatusView;

        @BeforeEach
        void setUp() {
                // Issue #6: base invoice view with outstanding balance of £100.00 (10000 minor
                // units)
                existingStatusView = new InvoiceStatusView();
                existingStatusView.setInvoiceId(INVOICE_ID);
                existingStatusView.setCurrentStatus(PaymentStatus.UNPAID);
                existingStatusView.setTotalAmountMinor(10_000L);
                existingStatusView.setOutstandingAmountMinor(10_000L);
                existingStatusView.setPaidAmountMinor(0L);
                existingStatusView.setPostingError(false);
                existingStatusView.setLastUpdated(Instant.now(TEST_CLOCK));
        }

        // ─────────────────────────────────────────────────────────────────────
        // AC1 — Full payment: outstandingAmountMinor → 0, status = Paid
        // ─────────────────────────────────────────────────────────────────────
        @Nested
        @DisplayName("AC1 — Full payment")
        class FullPayment {

                @Test
                @DisplayName("sets invoiceStatus=PAID and outstandingAmountMinor=0 when amountMinor equals totalAmountMinor")
                void whenFullPayment_thenStatusIsPaidAndOutstandingIsZero() {
                        // Arrange
                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-FULL-001")
                                        .amountMinor(10_000L)
                                        .currency("GBP")
                                        .outcomeType(PaymentOutcomeType.FULL_PAYMENT)
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        PaymentOutcomeResponse response = service.processPaymentOutcome(request);

                        // Assert — AC1
                        assertThat(response.getInvoiceStatus()).isEqualTo(PaymentStatus.PAID);
                        assertThat(response.getOutstandingAmountMinor()).isZero();
                        assertThat(response.getPaidAmountMinor()).isEqualTo(10_000L);
                        assertThat(response.isDuplicate()).isFalse();

                        verify(statusViewRepository).save(statusViewCaptor.capture());
                        InvoiceStatusView saved = statusViewCaptor.getValue();
                        assertThat(saved.getCurrentStatus()).isEqualTo(PaymentStatus.PAID);
                        assertThat(saved.getOutstandingAmountMinor()).isZero();
                }

                @Test
                @DisplayName("publishes InvoicePaymentRecorded event after full payment")
                void whenFullPayment_thenInvoicePaymentRecordedEventPublished() {
                        // Arrange
                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-FULL-002")
                                        .amountMinor(10_000L)
                                        .currency("GBP")
                                        .outcomeType(PaymentOutcomeType.FULL_PAYMENT)
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        service.processPaymentOutcome(request);

                        // Assert — InvoicePaymentRecorded emitted
                        verify(eventPublisher).publishEvent(
                                        argThat((Object evt) -> evt instanceof InvoicePaymentRecorded rec
                                                        && rec.invoiceId().equals(INVOICE_ID)
                                                        && rec.transactionId().equals("TXN-FULL-002")
                                                        && rec.amountMinor() == 10_000L));
                }

                @Test
                @DisplayName("saves posting intent to outbox after full payment (async posting)")
                void whenFullPayment_thenPostingIntentSavedToOutbox() {
                        // Arrange
                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-FULL-003")
                                        .amountMinor(10_000L)
                                        .currency("GBP")
                                        .outcomeType(PaymentOutcomeType.FULL_PAYMENT)
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        service.processPaymentOutcome(request);

                        // Assert — posting outbox entry persisted in same transaction
                        verify(outboxService).saveToOutbox(
                                        any(UUID.class),
                                        eq("Invoice"),
                                        eq(INVOICE_ID),
                                        any(String.class),
                                        any());
                }
        }

        // ─────────────────────────────────────────────────────────────────────
        // AC2 — Partial payment: status=PartiallyPaid, paidAmountMinor increments
        // ─────────────────────────────────────────────────────────────────────
        @Nested
        @DisplayName("AC2 — Partial payment")
        class PartialPayment {

                @Test
                @DisplayName("sets invoiceStatus=PARTIALLY_PAID and increments paidAmountMinor for partial payment")
                void whenPartialPayment_thenStatusIsPartiallyPaidAndAmountsCorrect() {
                        // Arrange
                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-PARTIAL-001")
                                        .amountMinor(4_000L) // £40.00 of £100.00
                                        .currency("GBP")
                                        .outcomeType(PaymentOutcomeType.PARTIAL_PAYMENT)
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        PaymentOutcomeResponse response = service.processPaymentOutcome(request);

                        // Assert — AC2
                        assertThat(response.getInvoiceStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
                        assertThat(response.getPaidAmountMinor()).isEqualTo(4_000L);
                        assertThat(response.getOutstandingAmountMinor()).isEqualTo(6_000L);

                        verify(statusViewRepository).save(statusViewCaptor.capture());
                        InvoiceStatusView saved = statusViewCaptor.getValue();
                        assertThat(saved.getCurrentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
                        assertThat(saved.getPaidAmountMinor()).isEqualTo(4_000L);
                        assertThat(saved.getOutstandingAmountMinor()).isEqualTo(6_000L);
                }

                @Test
                @DisplayName("saves posting intent for applied portion on partial payment")
                void whenPartialPayment_thenPostingIntentSavedForAppliedPortion() {
                        // Arrange
                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-PARTIAL-002")
                                        .amountMinor(4_000L)
                                        .currency("GBP")
                                        .outcomeType(PaymentOutcomeType.PARTIAL_PAYMENT)
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        service.processPaymentOutcome(request);

                        // Assert — outbox entry for the applied amount
                        verify(outboxService).saveToOutbox(
                                        any(UUID.class),
                                        eq("Invoice"),
                                        eq(INVOICE_ID),
                                        any(String.class),
                                        any());
                }
        }

        // ─────────────────────────────────────────────────────────────────────
        // AC3 — Duplicate transactionId: strict no-op
        // ─────────────────────────────────────────────────────────────────────
        @Nested
        @DisplayName("AC3 — Duplicate transactionId idempotency")
        class DuplicateTransactionId {

                @Test
                @DisplayName("returns PAID status and sets duplicate=true when transactionId already processed")
                void whenDuplicateTransactionId_thenNoOpAndDuplicateFlagSet() {
                        // Arrange — invoice already fully paid (simulates prior processing)
                        existingStatusView.setCurrentStatus(PaymentStatus.PAID);
                        existingStatusView.setOutstandingAmountMinor(0L);
                        existingStatusView.setPaidAmountMinor(10_000L);
                        existingStatusView.setLatestTransactionReference("TXN-DUP-001");

                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-DUP-001") // same as already processed
                                        .amountMinor(10_000L)
                                        .currency("GBP")
                                        .outcomeType(PaymentOutcomeType.FULL_PAYMENT)
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        PaymentOutcomeResponse response = service.processPaymentOutcome(request);

                        // Assert — AC3: no state change
                        assertThat(response.isDuplicate()).isTrue();
                        assertThat(response.getInvoiceStatus()).isEqualTo(PaymentStatus.PAID);

                        // No mutations should occur
                        verify(statusViewRepository, never()).save(any());
                        verify(outboxService, never()).saveToOutbox(any(), any(), any(), any(), any());
                }

                @Test
                @DisplayName("falls back to idempotencyKey when transactionId is absent")
                void whenIdempotencyKeyFallback_thenUsedForDuplicateDetection() {
                        // Arrange — invoice already paid, keyed by idempotencyKey fallback
                        existingStatusView.setCurrentStatus(PaymentStatus.PAID);
                        existingStatusView.setOutstandingAmountMinor(0L);
                        existingStatusView.setPaidAmountMinor(10_000L);
                        existingStatusView.setLatestIdempotencyKey("IDMP-KEY-001");

                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId(null) // no transactionId — fallback to idempotencyKey
                                        .idempotencyKey("IDMP-KEY-001")
                                        .amountMinor(10_000L)
                                        .currency("GBP")
                                        .outcomeType(PaymentOutcomeType.FULL_PAYMENT)
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        PaymentOutcomeResponse response = service.processPaymentOutcome(request);

                        // Assert — idempotencyKey fallback detected as duplicate
                        assertThat(response.isDuplicate()).isTrue();
                        verify(statusViewRepository, never()).save(any());
                }
        }

        // ─────────────────────────────────────────────────────────────────────
        // AC4 — Posting failure escalation: postingError=true after 10 retries
        // ─────────────────────────────────────────────────────────────────────
        @Nested
        @DisplayName("AC4 — Posting failure escalation")
        class PostingFailureEscalation {

                @Test
                @DisplayName("sets postingError=true after 10 retries exhausted")
                void whenPostingExhausted_thenPostingErrorSetOnInvoice() {
                        // Arrange — invoice already has 10 failed posting attempts
                        existingStatusView.setCurrentStatus(PaymentStatus.PAID);
                        existingStatusView.setOutstandingAmountMinor(0L);
                        existingStatusView.setPaidAmountMinor(10_000L);

                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        // Act — service is notified that posting has failed after maxRetries
                        service.handlePostingRetryExhausted(INVOICE_ID, "TXN-FAIL-001", 10);

                        // Assert — AC4
                        verify(statusViewRepository).save(statusViewCaptor.capture());
                        assertThat(statusViewCaptor.getValue().isPostingError()).isTrue();
                }

                @Test
                @DisplayName("emits InvoicePostingFailed event after retry exhaustion")
                void whenPostingExhausted_thenInvoicePostingFailedEventEmitted() {
                        // Arrange
                        existingStatusView.setCurrentStatus(PaymentStatus.PAID);
                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        // Act
                        service.handlePostingRetryExhausted(INVOICE_ID, "TXN-FAIL-002", 10);

                        // Assert — AC4: InvoicePostingFailed published
                        verify(eventPublisher).publishEvent(
                                        argThat((Object evt) -> evt instanceof InvoicePostingFailed f
                                                        && f.invoiceId().equals(INVOICE_ID)
                                                        && f.transactionId().equals("TXN-FAIL-002")
                                                        && f.retryCount() == 10));
                }

                @Test
                @DisplayName("creates reconciliation record after retry exhaustion")
                void whenPostingExhausted_thenReconciliationRecordCreated() {
                        // Arrange
                        existingStatusView.setCurrentStatus(PaymentStatus.PAID);
                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
                        when(reconciliationRecordRepository.save(any()))
                                        .thenAnswer(inv -> inv.getArgument(0));

                        // Act
                        service.handlePostingRetryExhausted(INVOICE_ID, "TXN-FAIL-003", 10);

                        // Assert — AC4: reconciliation record persisted
                        verify(reconciliationRecordRepository).save(reconciliationRecordCaptor.capture());
                        ReconciliationRecord rec = reconciliationRecordCaptor.getValue();
                        assertThat(rec.getInvoiceId()).isEqualTo(INVOICE_ID);
                        assertThat(rec.getTransactionId()).isEqualTo("TXN-FAIL-003");
                }
        }

        // ─────────────────────────────────────────────────────────────────────
        // AC5 — Chargeback: auto reversal + status=CHARGEBACK + immutable audit
        // ─────────────────────────────────────────────────────────────────────
        @Nested
        @DisplayName("AC5 — Chargeback event processing")
        class ChargebackProcessing {

                @Test
                @DisplayName("sets invoiceStatus=CHARGEBACK on explicit chargeback event")
                void whenChargebackEvent_thenStatusIsChargeback() {
                        // Arrange — invoice was PAID; chargeback arrives
                        existingStatusView.setCurrentStatus(PaymentStatus.PAID);
                        existingStatusView.setOutstandingAmountMinor(0L);
                        existingStatusView.setPaidAmountMinor(10_000L);

                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-CB-001")
                                        .amountMinor(10_000L)
                                        .currency("GBP")
                                        .outcomeType(PaymentOutcomeType.CHARGEBACK)
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        PaymentOutcomeResponse response = service.processPaymentOutcome(request);

                        // Assert — AC5
                        assertThat(response.getInvoiceStatus()).isEqualTo(PaymentStatus.CHARGEBACK);

                        verify(statusViewRepository).save(statusViewCaptor.capture());
                        assertThat(statusViewCaptor.getValue().getCurrentStatus())
                                        .isEqualTo(PaymentStatus.CHARGEBACK);
                }

                @Test
                @DisplayName("saves automatic reversal posting intent to outbox on chargeback")
                void whenChargebackEvent_thenReversalPostingIntentSavedToOutbox() {
                        // Arrange
                        existingStatusView.setCurrentStatus(PaymentStatus.PAID);
                        existingStatusView.setPaidAmountMinor(10_000L);
                        existingStatusView.setOutstandingAmountMinor(0L);

                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-CB-002")
                                        .amountMinor(10_000L)
                                        .currency("GBP")
                                        .outcomeType(PaymentOutcomeType.CHARGEBACK)
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        service.processPaymentOutcome(request);

                        // Assert — reversal posting outbox entry saved
                        verify(outboxService, times(1)).saveToOutbox(
                                        any(UUID.class),
                                        eq("Invoice"),
                                        eq(INVOICE_ID),
                                        argThat(eventType -> eventType.contains("Chargeback")
                                                        || eventType.contains("Reversal")),
                                        any());
                }

                @Test
                @DisplayName("no chargeback processing triggered without explicit chargeback outcome type")
                void whenNonChargebackOutcome_thenChargebackLogicNotTriggered() {
                        // Arrange
                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-NORMAL-001")
                                        .amountMinor(10_000L)
                                        .currency("GBP")
                                        .outcomeType(PaymentOutcomeType.FULL_PAYMENT) // NOT a chargeback
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        PaymentOutcomeResponse response = service.processPaymentOutcome(request);

                        // Assert — no chargeback status set
                        assertThat(response.getInvoiceStatus()).isNotEqualTo(PaymentStatus.CHARGEBACK);
                }
        }

        // ─────────────────────────────────────────────────────────────────────
        // AC6 — Overpayment: credit balance + invoice=Paid
        // ─────────────────────────────────────────────────────────────────────
        @Nested
        @DisplayName("AC6 — Overpayment creates customer credit balance")
        class OverpaymentCreditBalance {

                @Test
                @DisplayName("sets invoiceStatus=PAID and creates CustomerCredit for excess on overpayment")
                void whenOverpayment_thenInvoicePaidAndCreditCreated() {
                        // Arrange — invoice is £100 (10000 minor), payment is £120 (12000 minor)
                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
                        when(customerCreditRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-OVER-001")
                                        .amountMinor(12_000L) // £20 overpayment
                                        .currency("GBP")
                                        .outcomeType(PaymentOutcomeType.OVERPAYMENT)
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        PaymentOutcomeResponse response = service.processPaymentOutcome(request);

                        // Assert — AC6: invoice fully paid
                        assertThat(response.getInvoiceStatus()).isEqualTo(PaymentStatus.PAID);
                        assertThat(response.getOutstandingAmountMinor()).isZero();

                        // CustomerCredit created for overpayment excess (£20 = 2000 minor)
                        verify(customerCreditRepository).save(customerCreditCaptor.capture());
                        // The credit amount is stored in BigDecimal; 2000 minor = 20.00 major
                        assertThat(customerCreditCaptor.getValue().getCustomerId()).isEqualTo(CUSTOMER_ID);
                }

                @Test
                @DisplayName("credit amount equals amountMinor minus totalAmountMinor on overpayment")
                void whenOverpayment_thenCreditAmountIsExcessOnly() {
                        // Arrange — invoice £100, payment £120 → excess £20
                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
                        when(customerCreditRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-OVER-002")
                                        .amountMinor(12_000L)
                                        .currency("GBP")
                                        .outcomeType(PaymentOutcomeType.OVERPAYMENT)
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        service.processPaymentOutcome(request);

                        // Assert — credit is exactly the excess amount
                        verify(customerCreditRepository).save(customerCreditCaptor.capture());
                        CustomerCredit credit = customerCreditCaptor.getValue();
                        // 2000 minor units = 20.00 in standard monetary units (scale 2)
                        assertThat(credit.getAmount()).isEqualByComparingTo(java.math.BigDecimal.valueOf(20_00, 2));
                }
        }

        // ─────────────────────────────────────────────────────────────────────
        // AC7 — paidAmountMinor arithmetic must be correct in minor currency units
        // ─────────────────────────────────────────────────────────────────────
        @Nested
        @DisplayName("AC7 — Minor-unit arithmetic correctness (no floating point)")
        class MinorUnitArithmetic {

                @Test
                @DisplayName("correctly computes outstandingAmountMinor = totalAmountMinor - paidAmountMinor using long arithmetic")
                void whenTwoPartialPayments_thenOutstandingComputedExactlyWithLongArithmetic() {
                        // Arrange — invoice for 6667 minor units (prime number to expose rounding bugs)
                        existingStatusView.setTotalAmountMinor(6_667L);
                        existingStatusView.setOutstandingAmountMinor(6_667L);
                        existingStatusView.setPaidAmountMinor(0L);

                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> {
                                // Simulate persistence: mutate existingStatusView to reflect saved state
                                InvoiceStatusView v = inv.getArgument(0);
                                existingStatusView.setPaidAmountMinor(v.getPaidAmountMinor());
                                existingStatusView.setOutstandingAmountMinor(v.getOutstandingAmountMinor());
                                existingStatusView.setCurrentStatus(v.getCurrentStatus());
                                return v;
                        });

                        // First partial: 3333 minor units
                        PaymentOutcomeRequest first = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-MINOR-001")
                                        .amountMinor(3_333L)
                                        .currency("USD")
                                        .outcomeType(PaymentOutcomeType.PARTIAL_PAYMENT)
                                        .customerId(CUSTOMER_ID)
                                        .build();
                        service.processPaymentOutcome(first);

                        // Second partial: 3334 minor units (covers remaining)
                        PaymentOutcomeRequest second = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-MINOR-002")
                                        .amountMinor(3_334L)
                                        .currency("USD")
                                        .outcomeType(PaymentOutcomeType.PARTIAL_PAYMENT)
                                        .customerId(CUSTOMER_ID)
                                        .build();
                        service.processPaymentOutcome(second);

                        // Assert — AC7: outstanding is exactly 0 after both payments (3333+3334=6667)
                        verify(statusViewRepository, times(2)).save(statusViewCaptor.capture());
                        InvoiceStatusView finalState = statusViewCaptor.getAllValues().get(1);
                        assertThat(finalState.getOutstandingAmountMinor()).isZero();
                        assertThat(finalState.getPaidAmountMinor()).isEqualTo(6_667L);
                }

                @Test
                @DisplayName("paidAmountMinor stored as long — no BigDecimal floating point involved")
                void whenPaymentApplied_thenPaidAmountMinorIsLongNotBigDecimal() {
                        // Arrange
                        when(statusViewRepository.findByInvoiceId(INVOICE_ID))
                                        .thenReturn(Optional.of(existingStatusView));
                        when(statusViewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                        PaymentOutcomeRequest request = PaymentOutcomeRequest.builder()
                                        .invoiceId(INVOICE_ID)
                                        .transactionId("TXN-MINOR-003")
                                        .amountMinor(1L) // 1 minor unit test
                                        .currency("JPY")
                                        .outcomeType(PaymentOutcomeType.PARTIAL_PAYMENT)
                                        .customerId(CUSTOMER_ID)
                                        .build();

                        // Act
                        PaymentOutcomeResponse response = service.processPaymentOutcome(request);

                        // Assert — response carries long minor-unit amounts (not BigDecimal)
                        assertThat(response.getPaidAmountMinor()).isEqualTo(1L);
                        assertThat(response.getOutstandingAmountMinor()).isEqualTo(9_999L);
                }
        }
}
