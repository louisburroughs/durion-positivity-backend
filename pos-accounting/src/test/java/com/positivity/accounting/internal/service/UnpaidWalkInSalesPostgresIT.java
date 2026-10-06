package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.PostgresCommittingTestBase;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.CollectionsAnalyticsReport;
import com.positivity.accounting.internal.dto.PaymentApplicationGLPostingEvent;
import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.UnpaidWalkInSalesResponse;
import com.positivity.accounting.internal.dto.WalkInOpenInvoice;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.ExtCustomerParty;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtLocationReplica;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.ApplicationSource;
import com.positivity.accounting.internal.enums.WalkInResolution;
import com.positivity.accounting.internal.exception.CashCustomerCreditNotAllowedException;
import com.positivity.accounting.internal.handler.PaymentApplicationGLPostingEventHandler;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.EventOutboxRepository;
import com.positivity.accounting.internal.repository.ExtCustomerPartyRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoicePaymentReversalRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtLocationReplicaRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.domainevents.payment.PaymentReversedV1;
import com.positivity.domainevents.payment.PaymentSettledV1;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unpaid walk-in sales end to end on Postgres (#2508, CAP:550 S11): a settled CASH payment through the
 * production listener path and S2's automatic application to the unpaid walk-in sales read, the manual
 * refusal, and the {@code INVOICE_PAYMENT} path on a paid walk-in invoice. The CASH account is keyed on
 * the replica's {@code house_account} flag (V9).
 *
 * <p>Commits, so it has a database of its own. Requires Docker.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Unpaid walk-in sales (#2508, real Postgres)")
class UnpaidWalkInSalesPostgresIT extends PostgresCommittingTestBase {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, "unpaid-walk-in-sales");
        registerCommonProperties(registry);
        registry.add("pos.accounting.event-drainer.enabled", () -> "true");
        registry.add("pos.accounting.event-drainer.initial-delay-ms", () -> "3600000");
        registry.add("pos.accounting.failed-event-retry.initial-delay-ms", () -> "3600000");
    }

    /** Mid-day UTC in a month with no period row (open). 09:31 CDT on 2026-09-15 in America/Chicago. */
    private static final Instant SETTLED_AT = Instant.parse("2026-09-15T14:31:07Z");

    /** 19:00 CDT on 2026-09-09 in America/Chicago, the next day at UTC: long before any clock this test runs on. */
    private static final Instant SOLD_AT = Instant.parse("2026-09-10T00:00:00Z");

    private static final AtomicInteger UUID_COUNTER = new AtomicInteger(0x2508);

    private static UUID nextUuid() {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012x", UUID_COUNTER.getAndIncrement()));
    }

    /** Keeps outbox dispatch on the test thread: the tests drain it deterministically. */
    @MockitoBean
    private OutboxProcessor outboxProcessor;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Autowired
    private AccountingCalendarZoneResolver zoneResolver;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    @Autowired
    private LedgerCurrency ledgerCurrency;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private SettlementReconciliationService reconciliationService;

    @Autowired
    private PaymentApplicationService paymentApplicationService;

    @Autowired
    private ExtInvoicePaymentReversalRepository extInvoicePaymentReversalRepository;

    @Autowired
    private ExtInvoiceDepositCreditApplicationRepository extInvoiceDepositCreditApplicationRepository;

    @Autowired
    private KafkaFactIngestionRecorder ingestionRecorder;

    @Autowired
    private AutomaticPaymentApplicationService automaticPaymentApplicationService;

    @Autowired
    private UnpaidWalkInSalesService unpaidWalkInSalesService;

    @Autowired
    private ReceivablesWorklistService receivablesWorklistService;

    @Autowired
    private FinancialReportingService financialReportingService;

    @Autowired
    private AccountingAnalyticsService analyticsService;

    @Autowired
    private EventIngestionService eventIngestionService;

    @Autowired
    private ReceivedAccountingEventDrainer drainer;

    @Autowired
    private PaymentApplicationGLPostingEventHandler cashReceiptHandler;

    @Autowired
    private AccountingEventRepository accountingEventRepository;

    @Autowired
    private ReprocessingAttemptHistoryRepository historyRepository;

    @Autowired
    private ReceivablePaymentRepository receivablePaymentRepository;

    @Autowired
    private PaymentApplicationRepository paymentApplicationRepository;

    @Autowired
    private PaymentApplicationReversalRepository reversalRepository;

    @Autowired
    private CustomerCreditRepository customerCreditRepository;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private ExtCustomerPartyRepository extCustomerPartyRepository;

    @Autowired
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private AccountingSequenceRepository sequenceRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private AccountingPeriodRepository periodRepository;

    private SettlementEventsListener listener;
    private final tools.jackson.databind.ObjectMapper envelopeMapper = new tools.jackson.databind.ObjectMapper();
    private UUID cashParty;
    private UUID locationId;
    private UUID invoiceId;

    @BeforeEach
    void setUp() {
        clear();
        listener = new SettlementEventsListener(
                clock,
                envelopeMapper,
                processedEventRepository,
                reconciliationService,
                paymentApplicationService,
                extInvoicePaymentReversalRepository,
                extInvoiceDepositCreditApplicationRepository,
                ledgerCurrency,
                ingestionRecorder,
                automaticPaymentApplicationService,
                meterRegistry,
                transactionManager,
                zoneResolver);
        cashParty = nextUuid();
        locationId = nextUuid();
        invoiceId = nextUuid();
        extCustomerPartyRepository.save(ExtCustomerParty.builder()
                .partyId(cashParty)
                .partyType("COMMERCIAL")
                .displayName("Walk-in customer")
                .customerNumber("CASH")
                .houseAccount("CASH_SALE")
                .status("ACTIVE")
                .aggregateVersion(1L)
                .updatedAt(SETTLED_AT)
                .build());
        extLocationReplicaRepository.save(ExtLocationReplica.builder()
                .locationId(locationId)
                .name("Main Street")
                .code("LOC-107")
                .timezone("America/Chicago")
                .active(true)
                .aggregateVersion(1L)
                .updatedAt(SETTLED_AT)
                .build());
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private void clear() {
        journalEntryRepository.deleteAll();
        historyRepository.deleteAll();
        outboxRepository.deleteAll();
        customerCreditRepository.deleteAll();
        extInvoicePaymentReversalRepository.deleteAll();
        reversalRepository.deleteAll();
        paymentApplicationRepository.deleteAll();
        receivablePaymentRepository.deleteAll();
        accountingEventRepository.deleteAll();
        extInvoiceRepository.deleteAll();
        extCustomerPartyRepository.deleteAll();
        extLocationReplicaRepository.deleteAll();
        processedEventRepository.deleteAll();
        periodRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        sequenceRepository.deleteAll();
    }

    @Test
    @DisplayName("AC4: a settled CASH payment of 50.00 against a walk-in invoice with 45.00 open applies 45.00"
            + " (Dr 1090 / Cr 1200), creates no credit, is listed with 5.00 unapplied and raises the overpayment")
    void criterion4_settledCashExcessLeftUnapplied() {
        seedInvoice("45.00");
        double overpaymentsBefore = overpayments();
        UUID intent = nextUuid();

        consume(nextUuid(), fact(intent, "50.00"));

        assertThat(paymentApplicationRepository.findAll()).singleElement().satisfies(application -> {
            assertThat(application.getApplicationSource()).isEqualTo(ApplicationSource.PAYMENT_SETTLED);
            assertThat(application.getAppliedAmount()).isEqualByComparingTo("45.00");
        });
        assertThat(customerCreditRepository.count()).isZero();
        ReceivablePayment payment = receivablePaymentRepository.findById(intent).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(ReceivablePaymentStatus.AVAILABLE);
        assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("5.00");
        assertThat(overpayments() - overpaymentsBefore).isEqualTo(1.0);
        assertThat(accountingEventRepository.findAll())
                .as("an application writes no outcome row, nor does the walk-in excess")
                .isEmpty();

        drainOutbox();
        assertThat(postings()).containsExactly(new Posting("1090", "1200", "45.00"));

        UnpaidWalkInSalesResponse read = unpaidWalkInSalesService.read();
        assertThat(read.isHouseAccountKnown()).isTrue();
        assertThat(read.getBalance()).isEqualByComparingTo("0.00");
        assertThat(read.getOpenInvoices()).isEmpty();
        assertThat(read.getNeedsAttention().getCount()).isZero();
        assertThat(read.getUnappliedPayments()).singleElement().satisfies(unapplied -> {
            assertThat(unapplied.getPaymentId()).isEqualTo(intent);
            assertThat(unapplied.getUnappliedAmount()).isEqualByComparingTo("5.00");
            assertThat(unapplied.getPaymentReference()).isEqualTo("INV-W1");
        });

        // The customer views leave the walk-in sale out, through the real exclusion queries (AC7, AC8).
        assertThat(financialReportingService
                        .generateAgedReceivables(LocalDate.of(2026, 9, 30))
                        .getRows())
                .isEmpty();
        CollectionsAnalyticsReport collections =
                analyticsService.getCollectionsAnalytics(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertThat(collections.getInvoiced()).isEqualByComparingTo("0.00");
        assertThat(collections.getCollected()).isEqualByComparingTo("0.00");
        assertThat(collections.getApplicationReversals()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an unpaid walk-in invoice past its business day is the needs-attention item, dated in its"
            + " location's time zone")
    void openWalkInInvoiceNeedsAttention() {
        seedInvoice("40.00");

        UnpaidWalkInSalesResponse read = unpaidWalkInSalesService.read();

        assertThat(read.getBalance()).isEqualByComparingTo("40.00");
        assertThat(read.getCustomerNumber()).isEqualTo("CASH");
        WalkInOpenInvoice open = read.getOpenInvoices().getFirst();
        assertThat(open.getInvoiceNumber()).isEqualTo("INV-W1");
        assertThat(open.getLocationCode()).isEqualTo("LOC-107");
        assertThat(open.getSaleDate()).as("19:00 CDT the day before at UTC").isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(open.isTimezoneFallback()).isFalse();
        assertThat(open.isBusinessDayEnded()).isTrue();
        assertThat(open.getResolutions()).containsExactly(WalkInResolution.COLLECT, WalkInResolution.CREDIT_MEMO);
        assertThat(read.getNeedsAttention().getCount()).isEqualTo(1);
        assertThat(read.getNeedsAttention().getAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("AC5: a person applying a CASH payment with an overpayment gets CASH_CUSTOMER_CREDIT_NOT_ALLOWED;"
            + " nothing is applied or credited")
    void criterion5_manualOverpaymentRefused() {
        seedInvoice("45.00");
        UUID paymentId = nextUuid();
        inTransaction(() -> paymentApplicationService.handlePaymentCleared(
                paymentId, cashParty, "USD", new BigDecimal("50.00"), SETTLED_AT, nextUuid(), invoiceId, "CASH"));
        PaymentApplicationRequest request = new PaymentApplicationRequest(
                "manual-2508",
                List.of(new PaymentApplicationRequest.InvoiceApplication(invoiceId, new BigDecimal("50.00"))),
                null);

        assertThatThrownBy(() -> paymentApplicationService.applyPaymentToInvoices(paymentId, request))
                .isInstanceOf(CashCustomerCreditNotAllowedException.class)
                .hasMessageStartingWith("Walk-in overpayments are refunded, not kept as credit");

        assertThat(paymentApplicationRepository.count()).isZero();
        assertThat(customerCreditRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertThat(receivablePaymentRepository.findById(paymentId).orElseThrow().getUnappliedAmount())
                .isEqualByComparingTo("50.00");
        UnpaidWalkInSalesResponse read = unpaidWalkInSalesService.read();
        assertThat(read.getBalance()).isEqualByComparingTo("45.00");
        assertThat(read.getUnappliedPayments())
                .singleElement()
                .satisfies(
                        unapplied -> assertThat(unapplied.getUnappliedAmount()).isEqualByComparingTo("50.00"));
    }

    @Test
    @DisplayName("AC5: an INVOICE_PAYMENT for a walk-in invoice already paid in full creates no credit; the"
            + " payment is listed as unapplied and the event is processed")
    void criterion5_invoicePaymentOnPaidWalkInInvoice() {
        seedInvoice("45.00");
        consume(nextUuid(), fact(nextUuid(), "45.00"));
        double overpaymentsBefore = overpayments();

        UUID secondPayment = nextUuid();
        UUID eventId = submitInvoicePayment(secondPayment, "20.00");
        assertThat(drainer.drainBoundTenant()).isEqualTo(1);

        AccountingEvent event = accountingEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(customerCreditRepository.count()).isZero();
        ReceivablePayment payment =
                receivablePaymentRepository.findById(secondPayment).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(ReceivablePaymentStatus.AVAILABLE);
        assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("20.00");
        assertThat(overpayments() - overpaymentsBefore).isEqualTo(1.0);
        assertThat(unpaidWalkInSalesService.read().getUnappliedPayments())
                .singleElement()
                .satisfies(unapplied -> assertThat(unapplied.getPaymentId()).isEqualTo(secondPayment));
    }

    @Test
    @DisplayName("review #2553: refunding the 5.00 excess through pos-invoice takes it off the payment: it leaves"
            + " the read, cannot be applied again, the 45.00 application stands, and a refund replay changes"
            + " nothing")
    void refundedExcessLeavesTheRead() {
        seedInvoice("45.00");
        UUID intent = nextUuid();
        consume(nextUuid(), fact(intent, "50.00"));
        assertThat(unpaidWalkInSalesService.read().getUnappliedPayments()).hasSize(1);

        UUID refundId = nextUuid();
        consumeRefund(nextUuid(), refundId, intent, "5.00");

        ReceivablePayment payment = receivablePaymentRepository.findById(intent).orElseThrow();
        assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("0.00");
        assertThat(payment.getStatus()).isEqualTo(ReceivablePaymentStatus.FULLY_APPLIED);
        assertThat(unpaidWalkInSalesService.read().getUnappliedPayments()).isEmpty();
        assertThat(paymentApplicationRepository.findAll())
                .singleElement()
                .satisfies(application ->
                        assertThat(application.getAppliedAmount()).isEqualByComparingTo("45.00"));

        UUID other = nextUuid();
        extInvoiceRepository.save(ExtInvoice.builder()
                .invoiceId(other)
                .invoiceNumber("INV-W2")
                .locationId(locationId)
                .partyId(cashParty.toString())
                .status("FINALIZED")
                .total(new BigDecimal("5.00"))
                .invoiceCreatedAt(SOLD_AT)
                .finalizedAt(SOLD_AT)
                .aggregateVersion(1L)
                .updatedAt(SOLD_AT)
                .build());
        PaymentApplicationRequest again = new PaymentApplicationRequest(
                "manual-after-refund",
                List.of(new PaymentApplicationRequest.InvoiceApplication(other, new BigDecimal("5.00"))),
                null);
        assertThatThrownBy(() -> paymentApplicationService.applyPaymentToInvoices(intent, again))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not available");
        assertThat(paymentApplicationRepository.count()).isEqualTo(1);

        // The same refund re-published under a new event id: the refundId guard skips it.
        consumeRefund(nextUuid(), refundId, intent, "5.00");
        assertThat(receivablePaymentRepository.findById(intent).orElseThrow().getUnappliedAmount())
                .isEqualByComparingTo("0.00");
        assertThat(extInvoicePaymentReversalRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("#2556: a 5.00 refund processed before its settlement comes off when the settlement records the"
            + " payment: FULLY_APPLIED at 0.00, gone from both unapplied lists, and replays of either fact change"
            + " nothing")
    void refundBeforeSettlementLeavesNothingUnapplied() {
        seedInvoice("45.00");
        UUID intent = nextUuid();
        UUID refundId = nextUuid();
        double notRecordedBefore = unreleasedRefunds("payment_not_recorded");
        double exceedsBefore = unreleasedRefunds("exceeds_remainder");

        consumeRefund(nextUuid(), refundId, intent, "5.00");
        assertThat(receivablePaymentRepository.findById(intent)).isEmpty();
        assertThat(extInvoicePaymentReversalRepository.count()).isEqualTo(1);
        assertThat(unreleasedRefunds("payment_not_recorded") - notRecordedBefore)
                .isEqualTo(1.0);

        UUID settlementEventId = nextUuid();
        consume(settlementEventId, fact(intent, "50.00"));

        assertRefundedSettlement(intent);
        assertThat(unreleasedRefunds("exceeds_remainder") - exceedsBefore).isZero();

        // The settlement redelivered, and re-published under a new event id; the refund re-published.
        consume(settlementEventId, fact(intent, "50.00"));
        consume(nextUuid(), fact(intent, "50.00"));
        consumeRefund(nextUuid(), refundId, intent, "5.00");

        assertRefundedSettlement(intent);
        assertThat(extInvoicePaymentReversalRepository.count()).isEqualTo(1);
        assertThat(unreleasedRefunds("payment_not_recorded") - notRecordedBefore)
                .isEqualTo(1.0);
        assertThat(unreleasedRefunds("exceeds_remainder") - exceedsBefore).isZero();
    }

    @Test
    @DisplayName("#2556: a refund before its settlement that is more than the application leaves releases the"
            + " remainder only; the 45.00 application stands, the invoice still shows paid, and it is raised")
    void refundBeforeSettlementAboveTheRemainderIsRaised() {
        seedInvoice("45.00");
        UUID intent = nextUuid();
        double exceedsBefore = unreleasedRefunds("exceeds_remainder");

        consumeRefund(nextUuid(), nextUuid(), intent, "50.00");
        consume(nextUuid(), fact(intent, "50.00"));

        assertRefundedSettlement(intent);
        assertThat(unpaidWalkInSalesService.read().getBalance()).isEqualByComparingTo("0.00");
        assertThat(unreleasedRefunds("exceeds_remainder") - exceedsBefore).isEqualTo(1.0);
    }

    /** The 50.00 payment applied 45.00 to INV-W1 and has nothing left: in neither unapplied list. */
    private void assertRefundedSettlement(UUID intent) {
        ReceivablePayment payment = receivablePaymentRepository.findById(intent).orElseThrow();
        assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("0.00");
        assertThat(payment.getStatus()).isEqualTo(ReceivablePaymentStatus.FULLY_APPLIED);
        assertThat(paymentApplicationRepository.findAll()).singleElement().satisfies(application -> {
            assertThat(application.getApplicationSource()).isEqualTo(ApplicationSource.PAYMENT_SETTLED);
            assertThat(application.getAppliedAmount()).isEqualByComparingTo("45.00");
        });
        assertThat(customerCreditRepository.count()).isZero();
        assertThat(unpaidWalkInSalesService.read().getUnappliedPayments()).isEmpty();
        assertThat(receivablesWorklistService.listUnappliedPayments(null, 0, 50).getItems())
                .noneSatisfy(row -> assertThat(row.getPaymentId()).isEqualTo(intent));
    }

    private double unreleasedRefunds(String reason) {
        Counter counter = meterRegistry
                .getObject()
                .find("accounting.refund.unreleased")
                .tag("reason", reason)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void consumeRefund(UUID eventId, UUID refundId, UUID intent, String amount) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventType", PaymentReversedV1.EVENT_TYPE);
        envelope.put("eventId", eventId.toString());
        envelope.put("schemaVersion", PaymentReversedV1.SCHEMA_VERSION);
        envelope.put(
                "payload",
                new PaymentReversedV1(
                        intent,
                        refundId,
                        invoiceId,
                        null,
                        cashParty.toString(),
                        "REFUND",
                        new BigDecimal(amount),
                        "USD",
                        "overpayment",
                        SETTLED_AT.plusSeconds(3600)));
        listener.onPaymentEvent(envelopeMapper.writeValueAsString(envelope));
    }

    private double overpayments() {
        Counter counter =
                meterRegistry.getObject().find("accounting.walk_in.overpayment").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void consume(UUID eventId, PaymentSettledV1 fact) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventType", PaymentSettledV1.EVENT_TYPE);
        envelope.put("eventId", eventId.toString());
        envelope.put("schemaVersion", PaymentSettledV1.SCHEMA_VERSION);
        envelope.put("payload", fact);
        listener.onPaymentEvent(envelopeMapper.writeValueAsString(envelope));
    }

    private PaymentSettledV1 fact(UUID intent, String amount) {
        return new PaymentSettledV1(
                intent,
                invoiceId,
                "INV-W1",
                null,
                null,
                cashParty.toString(),
                "CASH",
                new BigDecimal(amount),
                "USD",
                null,
                null,
                SETTLED_AT);
    }

    private void seedInvoice(String total) {
        extInvoiceRepository.save(ExtInvoice.builder()
                .invoiceId(invoiceId)
                .invoiceNumber("INV-W1")
                .locationId(locationId)
                .partyId(cashParty.toString())
                .status("FINALIZED")
                .total(new BigDecimal(total))
                .invoiceCreatedAt(SOLD_AT)
                .finalizedAt(SOLD_AT)
                .aggregateVersion(1L)
                .updatedAt(SOLD_AT)
                .build());
    }

    private UUID submitInvoicePayment(UUID paymentId, String amount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentId", paymentId.toString());
        payload.put("invoiceId", invoiceId.toString());
        payload.put("amountPaid", new BigDecimal(amount));
        payload.put("currency", "USD");
        payload.put("paidAt", SETTLED_AT.toString());
        Map<String, Object> request = new HashMap<>();
        request.put("eventType", InvoicePaymentEventProcessor.EVENT_TYPE);
        request.put("organizationId", nextUuid());
        request.put("sourceSystem", "IT_2508");
        request.put("payload", payload);
        return eventIngestionService.submitEvent(request).getEventId();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void drainOutbox() {
        for (EventOutbox outbox : outboxRepository.findAll()) {
            if (!PaymentApplicationGLPostingEvent.class.getName().equals(outbox.getEventType())) {
                continue;
            }
            try {
                cashReceiptHandler.onPaymentApplicationGLPosting(
                        objectMapper.readValue(outbox.getPayload(), PaymentApplicationGLPostingEvent.class));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Failed to deserialize outbox payload", e);
            }
        }
    }

    /** One two-line journal entry: debit account, credit account and amount. */
    private record Posting(String debit, String credit, String amount) {}

    private List<Posting> postings() {
        return inTransaction(() -> {
            Map<UUID, String> codes = new HashMap<>();
            glAccountRepository
                    .findAll()
                    .forEach(account -> codes.put(account.getGlAccountId(), account.getAccountCode()));
            List<Posting> postings = new ArrayList<>();
            for (JournalEntry entry : journalEntryRepository.findAll()) {
                String debit = null;
                String credit = null;
                BigDecimal amount = BigDecimal.ZERO;
                for (JournalEntryLine line : entry.getLines()) {
                    if (line.getDebitAmount() != null && line.getDebitAmount().signum() > 0) {
                        debit = codes.get(line.getGlAccountId());
                        amount = line.getDebitAmount();
                    }
                    if (line.getCreditAmount() != null && line.getCreditAmount().signum() > 0) {
                        credit = codes.get(line.getGlAccountId());
                    }
                }
                postings.add(new Posting(
                        debit,
                        credit,
                        amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()));
            }
            return postings;
        });
    }
}
