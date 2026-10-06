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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.config.LedgerCurrency;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
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

    @Mock
    private KafkaFactIngestionRecorder ingestionRecorder;

    @Mock
    private AutomaticPaymentApplicationService automaticPaymentApplicationService;

    private String envelope(String eventId, PaymentSettledV1 payload) {
        return mapper.writeValueAsString(
                Map.of("eventType", PaymentSettledV1.EVENT_TYPE, "eventId", eventId, "payload", payload));
    }

    private PaymentSettledV1 settled(String partyId) {
        return settled(partyId, "USD");
    }

    private PaymentSettledV1 settled(String partyId, String currencyCode) {
        return new PaymentSettledV1(
                PAYMENT_INTENT_ID,
                INVOICE_ID,
                "INV-1001",
                null,
                null,
                partyId,
                "CARD",
                new BigDecimal("150.00"),
                currencyCode,
                "stripe",
                "txn_abc123",
                Instant.parse("2026-08-27T00:00:00Z"));
    }

    /**
     * A settled payment without a usable party (#2508, §4.4 item 1): skipped either way, never given an
     * invented customer; the envelope's schema version decides the severity.
     */
    @Nested
    @DisplayName("party-missing severity by schema version (#2508)")
    class PartyMissingSeverity {

        @Mock
        private PaymentApplicationService paymentApplicationService;

        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
        private final Logger listenerLogger = (Logger) LoggerFactory.getLogger(SettlementEventsListener.class);

        @BeforeEach
        void setUp() {
            when(processedEventRepository.existsById(anyString())).thenReturn(false);
            logs.start();
            listenerLogger.addAppender(logs);
        }

        @AfterEach
        void tearDown() {
            listenerLogger.detachAppender(logs);
        }

        @SuppressWarnings("unchecked")
        private SettlementEventsListener listener() {
            ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(registry);
            return new SettlementEventsListener(
                    CLOCK,
                    mapper,
                    processedEventRepository,
                    reconciliationService,
                    paymentApplicationService,
                    extInvoicePaymentReversalRepository,
                    extInvoiceDepositCreditApplicationRepository,
                    new LedgerCurrency("USD"),
                    ingestionRecorder,
                    automaticPaymentApplicationService,
                    provider,
                    org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
        }

        private String versioned(int schemaVersion, PaymentSettledV1 payload) {
            Map<String, Object> envelope = new java.util.HashMap<>();
            envelope.put("eventType", PaymentSettledV1.EVENT_TYPE);
            envelope.put("eventId", EVENT_ID);
            envelope.put("schemaVersion", schemaVersion);
            envelope.put("payload", payload);
            return mapper.writeValueAsString(envelope);
        }

        private double count(String name) {
            return registry.find(name).counters().stream()
                    .mapToDouble(io.micrometer.core.instrument.Counter::count)
                    .sum();
        }

        @Test
        @DisplayName("AC6: schemaVersion 2 with no party: no receivable, an ERROR naming the payment and invoice,"
                + " and payment.settled.party_missing_defect increments")
        void versionTwoWithoutPartyIsADefect() {
            listener().onPaymentEvent(versioned(2, settled(null)));

            verify(paymentApplicationService, never())
                    .handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any());
            verify(processedEventRepository).save(any(ProcessedEvent.class));
            assertThat(count("payment.settled.party_missing_defect")).isEqualTo(1.0);
            assertThat(count("payment.settled.unmappable")).isZero();
            assertThat(logs.list)
                    .filteredOn(event -> event.getLevel() == Level.ERROR)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage())
                            .contains(PAYMENT_INTENT_ID.toString())
                            .contains("INV-1001"));
        }

        @Test
        @DisplayName("schemaVersion 2 with a non-UUID party is the same defect")
        void versionTwoWithUnusablePartyIsADefect() {
            listener().onPaymentEvent(versioned(2, settled("not-a-uuid")));

            assertThat(count("payment.settled.party_missing_defect")).isEqualTo(1.0);
            assertThat(count("payment.settled.unmappable")).isZero();
        }

        @Test
        @DisplayName("AC6: schemaVersion 1 with no party keeps the legacy WARN and payment.settled.unmappable")
        void versionOneWithoutPartyIsLegacy() {
            listener().onPaymentEvent(versioned(1, settled(null)));

            verify(paymentApplicationService, never())
                    .handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any());
            assertThat(count("payment.settled.unmappable")).isEqualTo(1.0);
            assertThat(count("payment.settled.party_missing_defect")).isZero();
            assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.ERROR);
            assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN);
        }

        @Test
        @DisplayName("an envelope without schemaVersion reads as version 1 (legacy)")
        void missingVersionIsLegacy() {
            listener().onPaymentEvent(envelope(EVENT_ID, settled(null)));

            assertThat(count("payment.settled.unmappable")).isEqualTo(1.0);
            assertThat(count("payment.settled.party_missing_defect")).isZero();
        }
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
                    new LedgerCurrency("USD"),
                    ingestionRecorder,
                    automaticPaymentApplicationService,
                    mock(ObjectProvider.class),
                    org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
        }

        @BeforeEach
        void stubNotProcessed() {
            when(processedEventRepository.existsById(anyString())).thenReturn(false);
        }

        @Test
        @DisplayName(
                "maps a payment.payment.settled event onto handlePaymentCleared, with its invoice and method (#2502)")
        void mapsAndDelegates() {
            when(paymentApplicationService.handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any()))
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
                            org.mockito.ArgumentMatchers.eq(UUID.fromString(EVENT_ID)),
                            org.mockito.ArgumentMatchers.eq(INVOICE_ID),
                            org.mockito.ArgumentMatchers.eq("CARD"));
            // BigDecimal.equals() is scale-sensitive; the JSON round-trip through the envelope is not
            // guaranteed to preserve trailing zeros, so amount equivalence is asserted by value.
            assertThat(amountCaptor.getValue()).isEqualByComparingTo("150.00");
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("#2503: the recorded payment goes to the automatic application with the fact and the event id,"
                + " before the processed mark")
        void recordedPaymentIsAppliedAutomaticallyBeforeTheMark() {
            ReceivablePayment recorded = new ReceivablePayment();
            when(paymentApplicationService.handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(recorded);

            listener().onPaymentEvent(envelope(EVENT_ID, settled(PARTY_UUID.toString())));

            ArgumentCaptor<PaymentSettledV1> fact = ArgumentCaptor.forClass(PaymentSettledV1.class);
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                    paymentApplicationService, automaticPaymentApplicationService, processedEventRepository);
            order.verify(paymentApplicationService)
                    .handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any());
            order.verify(automaticPaymentApplicationService)
                    .applySettled(
                            org.mockito.ArgumentMatchers.same(recorded),
                            fact.capture(),
                            org.mockito.ArgumentMatchers.eq(EVENT_ID));
            order.verify(processedEventRepository).save(any(ProcessedEvent.class));
            assertThat(fact.getValue().paymentIntentId()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(fact.getValue().invoiceId()).isEqualTo(INVOICE_ID);
        }

        @Test
        @DisplayName("#2556: refunds stored before the settlement are released after the automatic application,"
                + " under this settlement's event id, before the processed mark")
        void storedRefundsAreReleasedAfterTheApplicationBeforeTheMark() {
            ReceivablePayment recorded = new ReceivablePayment();
            recorded.setPaymentId(PAYMENT_INTENT_ID);
            when(paymentApplicationService.handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(recorded);

            listener().onPaymentEvent(envelope(EVENT_ID, settled(PARTY_UUID.toString())));

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                    paymentApplicationService, automaticPaymentApplicationService, processedEventRepository);
            order.verify(paymentApplicationService)
                    .handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any());
            order.verify(automaticPaymentApplicationService).applySettled(any(), any(), any());
            order.verify(paymentApplicationService)
                    .releaseRefundsRecordedBeforeSettlement(PAYMENT_INTENT_ID, UUID.fromString(EVENT_ID));
            order.verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("#2503: an automatic-application failure propagates unmarked, for container retry/DLQ")
        void automaticApplicationFailurePropagatesUnmarked() {
            when(paymentApplicationService.handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ReceivablePayment());
            doThrow(new IllegalStateException("conflict"))
                    .when(automaticPaymentApplicationService)
                    .applySettled(any(), any(), any());

            assertThatThrownBy(() -> listener().onPaymentEvent(envelope(EVENT_ID, settled(PARTY_UUID.toString()))))
                    .hasMessage("conflict");

            verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("#2503 AC10: a foreign currency or a missing party never reaches the automatic application")
        void heldOrSkippedFactsAreNotAppliedAutomatically() {
            listener().onPaymentEvent(envelope(EVENT_ID, settled(PARTY_UUID.toString(), "EUR")));
            listener().onPaymentEvent(envelope("01960003-0000-7000-8000-000000000098", settled(null)));

            verify(automaticPaymentApplicationService, never()).applySettled(any(), any(), any());
        }

        @Test
        @DisplayName("a settled payment in another currency is held, never made an AVAILABLE payment (#2310)")
        void foreignCurrencyPaymentIsHeldNotMaterialized() {
            PaymentSettledV1 eur = settled(PARTY_UUID.toString(), "EUR");

            listener().onPaymentEvent(envelope(EVENT_ID, eur));

            verify(paymentApplicationService, never())
                    .handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any());
            verify(ingestionRecorder)
                    .recordCurrencyHeld(
                            org.mockito.ArgumentMatchers.eq("pos-invoice"),
                            org.mockito.ArgumentMatchers.eq(PaymentSettledV1.EVENT_TYPE),
                            org.mockito.ArgumentMatchers.eq(EVENT_ID),
                            org.mockito.ArgumentMatchers.eq(PAYMENT_INTENT_ID),
                            any(),
                            any(),
                            org.mockito.ArgumentMatchers.contains("EUR"));
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("skips a duplicate delivery without re-invoking handlePaymentCleared")
        void skipsDuplicate() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            listener().onPaymentEvent(envelope(EVENT_ID, settled(PARTY_UUID.toString())));

            verify(paymentApplicationService, never())
                    .handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any());
            verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("skips and marks processed an anonymous-counter-sale event with no party id (known gap)")
        void skipsWhenNoPartyId() {
            listener().onPaymentEvent(envelope(EVENT_ID, settled(null)));

            verify(paymentApplicationService, never())
                    .handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any());
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("skips and marks processed when partyId is not a parseable UUID")
        void skipsWhenPartyIdNotUuid() {
            listener().onPaymentEvent(envelope(EVENT_ID, settled("not-a-uuid")));

            verify(paymentApplicationService, never())
                    .handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any());
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

            verify(paymentApplicationService, never())
                    .handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any());
            verify(processedEventRepository).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("skips and marks processed when eventId is not a parseable UUID, without calling"
                + " handlePaymentCleared")
        void skipsWhenEventIdNotUuid() {
            String nonUuidEventId = "not-a-uuid";

            listener().onPaymentEvent(envelope(nonUuidEventId, settled(PARTY_UUID.toString())));

            verify(paymentApplicationService, never())
                    .handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any());
            ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
            verify(processedEventRepository).save(captor.capture());
            assertThat(captor.getValue().getEventId()).isEqualTo(nonUuidEventId);
        }

        @Test
        @DisplayName("propagates a handlePaymentCleared failure unmarked, for container retry/DLQ (finding 13 parity)")
        void propagatesServiceFailureUnmarked() {
            doThrow(new RuntimeException("db unavailable"))
                    .when(paymentApplicationService)
                    .handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any());

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
                    new LedgerCurrency("USD"),
                    ingestionRecorder,
                    automaticPaymentApplicationService,
                    mock(ObjectProvider.class),
                    org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
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
                    outboxService,
                    new LedgerCurrency("USD"),
                    new WalkInOverpaymentAlert(mock(ObjectProvider.class)),
                    extInvoicePaymentReversalRepository,
                    new RefundReleaseAlert(mock(ObjectProvider.class)));
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
