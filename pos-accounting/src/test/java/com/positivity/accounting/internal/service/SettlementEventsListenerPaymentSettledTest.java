package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoicePaymentReversalRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.domainevents.payment.PaymentSettledV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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
import org.springframework.beans.factory.ObjectProvider;

/**
 * Proves the {@code payment.payment.settled} intake added to {@link SettlementEventsListener} for
 * issue #1537 (D4) is a faithful replacement for the removed {@code payment.cleared.v1} listener:
 * same mapped arguments into the unchanged {@code
 * PaymentApplicationServiceImpl#handlePaymentCleared}, the same resulting {@link ReceivablePayment}
 * fields, the same {@code sourceEventId} idempotency, and the same "malformed payload
 * skipped-and-marked, business/DB error propagates unmarked" reliability contract (PR #977 finding
 * 13) that {@link SettlementListenersReliabilityTest} already proves for {@code
 * payment.settlement.reported}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementEventsListener — payment.payment.settled intake (issue #1537 D4)")
class SettlementEventsListenerPaymentSettledTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID PAYMENT_INTENT_ID = UUID.fromString("01960003-0000-7000-8000-000000000010");
    private static final UUID INVOICE_ID = UUID.fromString("01960003-0000-7000-8000-000000000011");
    private static final UUID PARTY_UUID = UUID.fromString("01960003-0000-7000-8000-000000000012");
    private static final String EVENT_ID = "01960003-0000-7000-8000-000000000099";

    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private SettlementReconciliationService reconciliationService;

    @Mock
    private ExtInvoicePaymentReversalRepository extInvoicePaymentReversalRepository;

    @Mock
    private ExtInvoiceDepositCreditApplicationRepository extInvoiceDepositCreditApplicationRepository;

    private String envelope(String eventId, PaymentSettledV1 payload) {
        return mapper.writeValueAsString(
                Map.of("eventType", PaymentSettledV1.EVENT_TYPE, "eventId", eventId, "payload", payload));
    }

    private PaymentSettledV1 settled(String partyId) {
        return new PaymentSettledV1(
                PAYMENT_INTENT_ID,
                INVOICE_ID,
                "INV-1001",
                null,
                null,
                partyId,
                "CARD",
                new BigDecimal("150.00"),
                "USD",
                "stripe",
                "txn_abc123",
                Instant.parse("2026-08-27T00:00:00Z"));
    }

    /** Dispatch/mapping unit tests against a mocked {@link PaymentApplicationService}. */
    @Nested
    @DisplayName("mapping and dispatch (mocked service)")
    class MappingAndDispatch {

        @Mock
        private PaymentApplicationService paymentApplicationService;

        private SettlementEventsListener listener() {
            return new SettlementEventsListener(
                    CLOCK,
                    mapper,
                    processedEventRepository,
                    reconciliationService,
                    paymentApplicationService,
                    extInvoicePaymentReversalRepository,
                    extInvoiceDepositCreditApplicationRepository,
                    mock(ObjectProvider.class));
        }

        @BeforeEach
        void stubNotProcessed() {
            when(processedEventRepository.existsById(anyString())).thenReturn(false);
        }

        @Test
        @DisplayName("maps a payment.payment.settled event onto handlePaymentCleared's exact legacy arguments")
        void mapsAndDelegates() {
            when(paymentApplicationService.handlePaymentCleared(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ReceivablePayment());

            listener().onPaymentEvent(envelope(EVENT_ID, settled(PARTY_UUID.toString())));

            ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(paymentApplicationService)
                    .handlePaymentCleared(
                            org.mockito.ArgumentMatchers.eq(PAYMENT_INTENT_ID),
                            org.mockito.ArgumentMatchers.eq(PARTY_UUID),
                            org.mockito.ArgumentMatchers.eq("USD"),
                            amountCaptor.capture(),
                            org.mockito.ArgumentMatchers.eq(Instant.parse("2026-08-27T00:00:00Z")),
                            org.mockito.ArgumentMatchers.eq(UUID.fromString(EVENT_ID)));
            // BigDecimal.equals() is scale-sensitive; the JSON round-trip through the envelope is not
            // guaranteed to preserve trailing zeros, so amount equivalence is asserted by value.
            assertThat(amountCaptor.getValue()).isEqualByComparingTo("150.00");
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("skips a duplicate delivery without re-invoking handlePaymentCleared")
        void skipsDuplicate() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            listener().onPaymentEvent(envelope(EVENT_ID, settled(PARTY_UUID.toString())));

            verify(paymentApplicationService, never()).handlePaymentCleared(any(), any(), any(), any(), any(), any());
            verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("skips and marks processed an anonymous-counter-sale event with no party id (known gap)")
        void skipsWhenNoPartyId() {
            listener().onPaymentEvent(envelope(EVENT_ID, settled(null)));

            verify(paymentApplicationService, never()).handlePaymentCleared(any(), any(), any(), any(), any(), any());
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("skips and marks processed when partyId is not a parseable UUID")
        void skipsWhenPartyIdNotUuid() {
            listener().onPaymentEvent(envelope(EVENT_ID, settled("not-a-uuid")));

            verify(paymentApplicationService, never()).handlePaymentCleared(any(), any(), any(), any(), any(), any());
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("rejects and marks processed a payload missing a required field despite valid JSON")
        void rejectsMissingRequiredField() {
            // amount omitted entirely — Jackson leaves it null; the record has no compact
            // constructor to reject it, so the listener's own defensive check must.
            String msg = mapper.writeValueAsString(Map.of(
                    "eventType",
                    PaymentSettledV1.EVENT_TYPE,
                    "eventId",
                    EVENT_ID,
                    "payload",
                    Map.of(
                            "paymentIntentId",
                            PAYMENT_INTENT_ID.toString(),
                            "invoiceId",
                            INVOICE_ID.toString(),
                            "partyId",
                            PARTY_UUID.toString(),
                            "methodType",
                            "CARD",
                            "currencyCode",
                            "USD",
                            "settledAt",
                            "2026-08-27T00:00:00Z")));

            listener().onPaymentEvent(msg);

            verify(paymentApplicationService, never()).handlePaymentCleared(any(), any(), any(), any(), any(), any());
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("skips and marks processed when eventId is not a parseable UUID, without calling"
                + " handlePaymentCleared")
        void skipsWhenEventIdNotUuid() {
            String nonUuidEventId = "not-a-uuid";

            listener().onPaymentEvent(envelope(nonUuidEventId, settled(PARTY_UUID.toString())));

            verify(paymentApplicationService, never()).handlePaymentCleared(any(), any(), any(), any(), any(), any());
            ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
            verify(processedEventRepository).save(captor.capture());
            assertThat(captor.getValue().getEventId()).isEqualTo(nonUuidEventId);
        }

        @Test
        @DisplayName("propagates a handlePaymentCleared failure unmarked, for container retry/DLQ (finding 13 parity)")
        void propagatesServiceFailureUnmarked() {
            doThrow(new RuntimeException("db unavailable"))
                    .when(paymentApplicationService)
                    .handlePaymentCleared(any(), any(), any(), any(), any(), any());

            assertThatThrownBy(() -> listener().onPaymentEvent(envelope(EVENT_ID, settled(PARTY_UUID.toString()))))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("db unavailable");

            verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
        }
    }

    /**
     * End-to-end equivalence: the real {@link PaymentApplicationServiceImpl} (unmodified,
     * unmocked) wired behind the listener, proving the {@link ReceivablePayment} it persists from a
     * mapped {@code payment.payment.settled} event is field-for-field what
     * {@code PaymentEventListenerConfigTest} proved the legacy {@code payment.cleared.v1} listener
     * produced from equivalent data.
     */
    @Nested
    @DisplayName("equivalence with the legacy payment.cleared.v1 receivable (real service, mocked repositories)")
    class EquivalenceWithLegacyReceivable {

        @Mock
        private ReceivablePaymentRepository receivablePaymentRepository;

        @Mock
        private PaymentApplicationRepository paymentApplicationRepository;

        @Mock
        private CustomerCreditRepository customerCreditRepository;

        @Mock
        private PaymentApplicationReversalRepository reversalRepository;

        @Mock
        private InvoiceBalanceCalculator invoiceBalanceCalculator;

        @Mock
        private OutboxService outboxService;

        private PaymentApplicationServiceImpl realService;

        private SettlementEventsListener listener() {
            return new SettlementEventsListener(
                    CLOCK,
                    mapper,
                    processedEventRepository,
                    reconciliationService,
                    realService,
                    extInvoicePaymentReversalRepository,
                    extInvoiceDepositCreditApplicationRepository,
                    mock(ObjectProvider.class));
        }

        @BeforeEach
        void wireRealService() {
            realService = new PaymentApplicationServiceImpl(
                    CLOCK,
                    receivablePaymentRepository,
                    paymentApplicationRepository,
                    customerCreditRepository,
                    reversalRepository,
                    invoiceBalanceCalculator,
                    outboxService);
            when(processedEventRepository.existsById(anyString())).thenReturn(false);
        }

        @Test
        @DisplayName(
                "materializes a ReceivablePayment with the exact fields the legacy PaymentCleared handler produced")
        void producesTheSameReceivablePaymentFields() {
            when(receivablePaymentRepository.existsBySourceEventId(UUID.fromString(EVENT_ID)))
                    .thenReturn(false);
            when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            listener().onPaymentEvent(envelope(EVENT_ID, settled(PARTY_UUID.toString())));

            ArgumentCaptor<ReceivablePayment> captor = ArgumentCaptor.forClass(ReceivablePayment.class);
            verify(receivablePaymentRepository).save(captor.capture());
            ReceivablePayment saved = captor.getValue();

            assertThat(saved.getPaymentId()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(saved.getCustomerId()).isEqualTo(PARTY_UUID);
            assertThat(saved.getCurrency()).isEqualTo("USD");
            assertThat(saved.getTotalAmount()).isEqualByComparingTo("150.00");
            assertThat(saved.getUnappliedAmount()).isEqualByComparingTo(saved.getTotalAmount());
            assertThat(saved.getStatus()).isEqualTo(ReceivablePaymentStatus.AVAILABLE);
            assertThat(saved.getClearedAt()).isEqualTo(Instant.parse("2026-08-27T00:00:00Z"));
            assertThat(saved.getSourceEventId()).isEqualTo(UUID.fromString(EVENT_ID));

            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("a redelivered payment.payment.settled event does not create a second ReceivablePayment")
        void redeliveryDoesNotDuplicate() {
            ReceivablePayment existing = new ReceivablePayment();
            existing.setPaymentId(PAYMENT_INTENT_ID);
            existing.setCustomerId(PARTY_UUID);
            existing.setCurrency("USD");
            existing.setTotalAmount(new BigDecimal("150.00"));
            existing.setUnappliedAmount(new BigDecimal("150.00"));
            existing.setStatus(ReceivablePaymentStatus.AVAILABLE);
            existing.setClearedAt(Instant.parse("2026-08-27T00:00:00Z"));
            existing.setSourceEventId(UUID.fromString(EVENT_ID));

            // First delivery: not yet processed at the processed_events layer, not yet a receivable.
            when(receivablePaymentRepository.existsBySourceEventId(UUID.fromString(EVENT_ID)))
                    .thenReturn(false);
            when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            listener().onPaymentEvent(envelope(EVENT_ID, settled(PARTY_UUID.toString())));
            verify(receivablePaymentRepository, org.mockito.Mockito.times(1)).save(any(ReceivablePayment.class));

            // Second delivery of the SAME eventId: processed_events already has it, so the listener
            // never re-enters handlePaymentCleared — the outer processed_events guard is the first
            // line of defense.
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);
            listener().onPaymentEvent(envelope(EVENT_ID, settled(PARTY_UUID.toString())));
            verify(receivablePaymentRepository, org.mockito.Mockito.times(1)).save(any(ReceivablePayment.class));

            // Belt-and-braces: even if processed_events were bypassed (e.g. a crash between the two
            // commits), handlePaymentCleared's own existsBySourceEventId guard returns the existing
            // row instead of creating a second one.
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(false);
            when(receivablePaymentRepository.existsBySourceEventId(UUID.fromString(EVENT_ID)))
                    .thenReturn(true);
            when(receivablePaymentRepository.findBySourceEventId(UUID.fromString(EVENT_ID)))
                    .thenReturn(Optional.of(existing));
            listener().onPaymentEvent(envelope(EVENT_ID, settled(PARTY_UUID.toString())));
            verify(receivablePaymentRepository, org.mockito.Mockito.times(1)).save(any(ReceivablePayment.class));
        }
    }
}
