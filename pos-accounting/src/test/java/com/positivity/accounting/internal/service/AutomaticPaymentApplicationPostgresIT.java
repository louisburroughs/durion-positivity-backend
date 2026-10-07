package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.PostgresCommittingTestBase;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.AutomaticPaymentApplicationRow;
import com.positivity.accounting.internal.dto.AutomaticPaymentApplicationsPage;
import com.positivity.accounting.internal.dto.CustomerCreditIssuanceGLPostingEvent;
import com.positivity.accounting.internal.dto.PaymentApplicationGLPostingEvent;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.dto.UnappliedPaymentRow;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.entity.CustomerCredit;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.ExtCustomerParty;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.ApplicationSource;
import com.positivity.accounting.internal.handler.CustomerCreditIssuanceGLPostingEventHandler;
import com.positivity.accounting.internal.handler.PaymentApplicationGLPostingEventHandler;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.EventOutboxRepository;
import com.positivity.accounting.internal.repository.ExtCustomerPartyRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoicePaymentReversalRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.domainevents.payment.PaymentSettledV1;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Criteria 1-8 and 12 of #2503 (CAP:550 S2) end to end on Postgres: a {@code payment.payment.settled}
 * envelope through the production listener path (built by hand, since the Kafka rails stay off in the
 * {@code pg} profile) to the application, the customer credit, the outbox work items drained through
 * their handlers, and the journal entries asserted by account code and date.
 *
 * <p>Commits, so it has a database of its own; the received-event drainer is switched on with its
 * poll pushed out of the way for the cross-path criterion, and the retry job is called directly.
 *
 * <p>Requires Docker.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Settled payments apply automatically (#2503, real Postgres)")
class AutomaticPaymentApplicationPostgresIT extends PostgresCommittingTestBase {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, "automatic-payment-application");
        registerCommonProperties(registry);
        registry.add("pos.accounting.event-drainer.enabled", () -> "true");
        registry.add("pos.accounting.event-drainer.initial-delay-ms", () -> "3600000");
        registry.add("pos.accounting.failed-event-retry.initial-delay-ms", () -> "3600000");
    }

    /** Mid-day UTC in a month with no period row (open), so the date is the same in any server zone. */
    private static final Instant SETTLED_AT = Instant.parse("2026-09-15T14:31:07Z");

    private static final AtomicInteger UUID_COUNTER = new AtomicInteger(0x2503);

    private static UUID nextUuid() {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012x", UUID_COUNTER.getAndIncrement()));
    }

    /** Keeps outbox dispatch on the test thread: the tests drain it deterministically. */
    @MockitoBean
    private OutboxProcessor outboxProcessor;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PaymentIntentLock paymentIntentLock;

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
    private AutomaticPaymentApplicationQueryService automaticQueryService;

    @Autowired
    private ReceivablesWorklistService receivablesWorklistService;

    @Autowired
    private EventIngestionService eventIngestionService;

    @Autowired
    private ReceivedAccountingEventDrainer drainer;

    @Autowired
    private FailedAccountingEventRetryJob retryJob;

    @Autowired
    private PaymentApplicationGLPostingEventHandler cashReceiptHandler;

    @Autowired
    private CustomerCreditIssuanceGLPostingEventHandler issuanceHandler;

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

    @Autowired
    private AccountingConfigurationRepository configurationRepository;

    @Autowired
    private AccountingConfigurationService configurationService;

    private SettlementEventsListener listener;
    private final tools.jackson.databind.ObjectMapper envelopeMapper = new tools.jackson.databind.ObjectMapper();
    private UUID customerId;
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
                paymentIntentLock,
                zoneResolver);
        customerId = nextUuid();
        invoiceId = nextUuid();
        extCustomerPartyRepository.save(ExtCustomerParty.builder()
                .partyId(customerId)
                .partyType("COMMERCIAL")
                .displayName("Rivera Trucking")
                .customerNumber("CUST-00412")
                .status("ACTIVE")
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
        reversalRepository.deleteAll();
        paymentApplicationRepository.deleteAll();
        receivablePaymentRepository.deleteAll();
        accountingEventRepository.deleteAll();
        extInvoiceRepository.deleteAll();
        extCustomerPartyRepository.deleteAll();
        processedEventRepository.deleteAll();
        periodRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        sequenceRepository.deleteAll();
        setZone("UTC");
    }

    // ===== criterion 1 =====

    @Test
    @DisplayName("1: a settled CARD payment of 115.00 for INV-1 is applied in full, dated settledAt, and posts"
            + " Dr 1090 / Cr 1200 115.00 on the settlement date")
    void criterion1_appliedAndPosted() {
        seedInvoice("115.00", customerId);
        UUID intent = nextUuid();

        consume(nextUuid(), fact(intent, "CARD", "115.00"));

        PaymentApplication application = singleApplication();
        assertThat(application.getApplicationSource()).isEqualTo(ApplicationSource.PAYMENT_SETTLED);
        assertThat(application.getApplicationTimestamp()).isEqualTo(SETTLED_AT);
        assertThat(application.getApplicationRequestId()).isEqualTo("PAYMENT_SETTLED:" + intent);
        assertThat(application.getAppliedAmount()).isEqualByComparingTo("115.00");
        assertThat(application.getCreatedBy()).isEqualTo("SYSTEM");
        ReceivablePayment payment = receivablePaymentRepository.findById(intent).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(ReceivablePaymentStatus.FULLY_APPLIED);
        assertThat(balanceDue()).isEqualByComparingTo("0.00");
        assertThat(settledRows()).as("an application writes no outcome row").isEmpty();

        drainOutbox();
        assertThat(postings()).containsExactly(new Posting("1090", "1200", "115.00", settledDate()));
    }

    // ===== criterion 2 =====

    @Test
    @DisplayName("2: a redelivery, or a re-publish under a new event id, adds no application, credit or entry;"
            + " the apply entry point replayed under the request id returns the recorded application")
    void criterion2_onceForTheSettlement() {
        seedInvoice("115.00", customerId);
        UUID intent = nextUuid();
        UUID eventId = nextUuid();
        PaymentSettledV1 fact = fact(intent, "CARD", "115.00");

        consume(eventId, fact);
        consume(eventId, fact); // redelivery: processed_events
        consume(nextUuid(), fact); // re-publish: a new envelope for the same settlement

        PaymentApplication application = singleApplication();
        assertThat(customerCreditRepository.count()).isZero();
        assertThat(outboxRepository.count()).isEqualTo(1);
        drainOutbox();
        assertThat(postings()).hasSize(1);

        // The request id itself is the guard (BR-4): the entry point replayed returns the recorded result.
        PaymentApplicationResponse replay = inTransaction(() -> paymentApplicationService.applyAutomatically(
                intent,
                invoiceId,
                new BigDecimal("115.00"),
                "PAYMENT_SETTLED:" + intent,
                SETTLED_AT,
                ApplicationSource.PAYMENT_SETTLED));
        assertThat(replay.getApplications())
                .singleElement()
                .satisfies(detail ->
                        assertThat(detail.getPaymentApplicationId()).isEqualTo(application.getPaymentApplicationId()));
        assertThat(paymentApplicationRepository.count()).isEqualTo(1);

        // A re-publish after the period closed still finds the recorded application first (review #2550):
        // no outcome row, nothing applied.
        closedPeriod("2026-09");
        consume(nextUuid(), fact);
        assertThat(settledRows()).isEmpty();
        assertThat(paymentApplicationRepository.count()).isEqualTo(1);
    }

    // ===== criterion 3 =====

    @Test
    @DisplayName("3: after an INVOICE_PAYMENT for the same payment nothing more is applied and no row is written;"
            + " in the reverse order the INVOICE_PAYMENT event is DUPLICATE_IGNORED")
    void criterion3_crossPath() {
        seedInvoice("230.00", customerId);

        // INVOICE_PAYMENT first.
        UUID first = nextUuid();
        submitInvoicePayment(first, "115.00");
        assertThat(drainer.drainBoundTenant()).isEqualTo(1);
        consume(nextUuid(), fact(first, "CARD", "115.00"));
        assertThat(paymentApplicationRepository.findAll())
                .singleElement()
                .satisfies(app -> assertThat(app.getApplicationSource()).isEqualTo(ApplicationSource.INVOICE_PAYMENT));
        assertThat(settledRows()).isEmpty();

        // The settled fact first.
        UUID second = nextUuid();
        consume(nextUuid(), fact(second, "CARD", "115.00"));
        UUID eventId = submitInvoicePayment(second, "115.00");
        assertThat(drainer.drainBoundTenant()).isEqualTo(1);
        AccountingEvent event = accountingEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(event.getIdempotencyOutcome()).isEqualTo("DUPLICATE_IGNORED");
        assertThat(paymentApplicationRepository.findAll())
                .extracting(PaymentApplication::getApplicationSource)
                .containsExactlyInAnyOrder(ApplicationSource.INVOICE_PAYMENT, ApplicationSource.PAYMENT_SETTLED);
        assertThat(balanceDue()).isEqualByComparingTo("0.00");
    }

    // ===== criterion 4 =====

    @Test
    @DisplayName("4: 120.00 against a balance of 115.00 applies 115.00, keeps 5.00 as credit, and posts both"
            + " entries on the settlement date")
    void criterion4_excessBecomesCredit() {
        seedInvoice("115.00", customerId);
        UUID intent = nextUuid();

        consume(nextUuid(), fact(intent, "CARD", "120.00"));

        assertThat(singleApplication().getAppliedAmount()).isEqualByComparingTo("115.00");
        CustomerCredit credit = customerCreditRepository.findAll().getFirst();
        assertThat(customerCreditRepository.count()).isEqualTo(1);
        assertThat(credit.getAmount()).isEqualByComparingTo("5.00");
        assertThat(credit.getRequestId()).isEqualTo("APPLY:PAYMENT_SETTLED:" + intent);
        // created_at is the audit time the row was written (ADR-0024), not settledAt; the credit's ledger
        // date is its issuance entry's, asserted below.
        assertThat(credit.getCreatedAt()).isAfter(SETTLED_AT);
        assertThat(receivablePaymentRepository.findById(intent).orElseThrow().getStatus())
                .isEqualTo(ReceivablePaymentStatus.FULLY_APPLIED);

        drainOutbox();
        assertThat(postings())
                .containsExactlyInAnyOrder(
                        new Posting("1090", "1200", "115.00", settledDate()),
                        new Posting("1090", "2300", "5.00", settledDate()));
    }

    // ===== criterion 5 =====

    @Test
    @DisplayName("5: an invoice of another customer is not applied; the payment stays AVAILABLE and listed, and"
            + " the row is SKIPPED / NOT_POSTABLE naming the invoice number")
    void criterion5_partyMismatch() {
        // Another customer with the same name: only the UUID tells them apart (BR-2).
        UUID namesake = nextUuid();
        extCustomerPartyRepository.save(ExtCustomerParty.builder()
                .partyId(namesake)
                .partyType("COMMERCIAL")
                .displayName("Rivera Trucking")
                .customerNumber("CUST-00977")
                .status("ACTIVE")
                .aggregateVersion(1L)
                .updatedAt(SETTLED_AT)
                .build());
        seedInvoice("115.00", namesake);
        UUID intent = nextUuid();

        consume(nextUuid(), fact(intent, "CARD", "115.00"));

        assertThat(paymentApplicationRepository.count()).isZero();
        assertThat(receivablePaymentRepository.findById(intent).orElseThrow().getStatus())
                .isEqualTo(ReceivablePaymentStatus.AVAILABLE);
        assertThat(receivablesWorklistService
                        .listUnappliedPayments(customerId, 0, 25)
                        .getItems())
                .extracting(UnappliedPaymentRow::getPaymentId)
                .containsExactly(intent);
        assertThat(settledRows()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(AccountingEventStatus.SKIPPED);
            assertThat(row.getFailureReasonCode()).isEqualTo("NOT_POSTABLE");
            assertThat(row.getErrorMessage()).isEqualTo("customer differs from invoice INV-1");
            assertThat(row.getDomainKeyId()).isEqualTo(intent.toString());
        });
    }

    // ===== criterion 6 =====

    @Test
    @DisplayName("6: a settlement in a closed period is SUSPENDED / PERIOD_CLOSED, the retry job leaves it, and a"
            + " reprocess after reopening applies it dated settledAt")
    void criterion6_closedPeriod() {
        seedInvoice("115.00", customerId);
        AccountingPeriod september = closedPeriod("2026-09");
        UUID intent = nextUuid();

        consume(nextUuid(), fact(intent, "CARD", "115.00"));

        assertThat(paymentApplicationRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        AccountingEvent held = settledRows().getFirst();
        assertThat(settledRows()).hasSize(1);
        assertThat(held.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(held.getFailureReasonCode()).isEqualTo("PERIOD_CLOSED");

        assertThat(retryJob.retryBoundTenant())
                .as("the retry job never picks PERIOD_CLOSED")
                .isZero();
        assertThat(paymentApplicationRepository.count()).isZero();

        september.setStatus(AccountingPeriodStatus.OPEN);
        periodRepository.save(september);
        AccountingEventResponse reprocessed = inTransaction(
                () -> eventIngestionService.reprocessEvent(held.getEventId(), new ReprocessEventRequest(), "ops-user"));

        assertThat(reprocessed.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(singleApplication().getApplicationTimestamp()).isEqualTo(SETTLED_AT);
        assertThat(receivablePaymentRepository.count())
                .as("the payment is never recorded again")
                .isEqualTo(1);
        drainOutbox();
        assertThat(postings()).containsExactly(new Posting("1090", "1200", "115.00", settledDate()));
    }

    // ===== criterion 7 =====

    @Test
    @DisplayName("7: an invoice not yet replicated is SUSPENDED / INVOICE_NOT_FOUND, and the first retry pass after"
            + " it arrives applies the payment")
    void criterion7_invoiceArrivesLater() {
        UUID intent = nextUuid();

        consume(nextUuid(), fact(intent, "CARD", "115.00"));

        AccountingEvent held = settledRows().getFirst();
        assertThat(held.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(held.getFailureReasonCode()).isEqualTo("INVOICE_NOT_FOUND");

        seedInvoice("115.00", customerId);
        assertThat(retryJob.retryBoundTenant()).isEqualTo(1);

        assertThat(accountingEventRepository
                        .findById(held.getEventId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(AccountingEventStatus.PROCESSED);
        PaymentApplication application = singleApplication();
        assertThat(application.getApplicationTimestamp()).isEqualTo(SETTLED_AT);
        assertThat(application.getCreatedBy()).isEqualTo("SYSTEM");
    }

    // ===== criterion 8 (and the read of criterion 9) =====

    @Test
    @DisplayName("8: an undone automatic application leaves the payment AVAILABLE; neither a redelivery nor a"
            + " reprocess applies it again, and the read shows it reversed without UNDO")
    void criterion8_undoSticks() {
        seedInvoice("115.00", customerId);
        UUID intent = nextUuid();
        PaymentSettledV1 fact = fact(intent, "CARD", "115.00");
        consume(nextUuid(), fact);
        PaymentApplication application = singleApplication();

        AutomaticPaymentApplicationRow before = onlyRow(true);
        assertThat(before.getInvoiceNumber()).isEqualTo("INV-1");
        assertThat(before.getCustomerDisplayName()).isEqualTo("Rivera Trucking");
        assertThat(before.getCustomerReference()).isEqualTo("CUST-00412");
        assertThat(before.getActions()).containsExactly("UNDO");
        assertThat(onlyRow(false).getActions()).isEmpty();

        paymentApplicationService.reversePaymentApplication(
                application.getPaymentApplicationId(), "Customer asked to keep it on account");
        assertThat(receivablePaymentRepository.findById(intent).orElseThrow().getStatus())
                .isEqualTo(ReceivablePaymentStatus.AVAILABLE);

        consume(nextUuid(), fact); // a re-publish of the same settlement
        // Stored as the ingestion recorder stores a fact: through the application's Jackson 3 mapper.
        Map<String, Object> stored =
                envelopeMapper.convertValue(fact, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        AutomaticPaymentApplicationService.Result reprocess =
                inTransaction(() -> automaticPaymentApplicationService.reapply(stored));

        assertThat(reprocess.outcome()).isEqualTo(AutomaticPaymentApplicationService.Outcome.ALREADY_APPLIED);
        assertThat(paymentApplicationRepository.count()).isEqualTo(1);
        assertThat(receivablePaymentRepository.findById(intent).orElseThrow().getStatus())
                .isEqualTo(ReceivablePaymentStatus.AVAILABLE);
        AutomaticPaymentApplicationRow after = onlyRow(true);
        assertThat(after.isReversed()).isTrue();
        assertThat(after.getReversedAt()).isNotNull();
        assertThat(after.getActions()).isEmpty();
    }

    @Test
    @DisplayName("7: an invoice that never arrives shares the retry cap: after max-retries passes the row is no longer"
            + " a retry candidate, and a manual reprocess applies it once the invoice arrives")
    void criterion7_invoiceNotFoundSharesTheRetryCap() {
        UUID intent = nextUuid();
        consume(nextUuid(), fact(intent, "CARD", "115.00"));
        AccountingEvent held = settledRows().getFirst();

        for (int pass = 0; pass < 3; pass++) { // pos.accounting.failed-event-retry.max-retries default
            assertThat(retryJob.retryBoundTenant()).as("pass %s", pass).isEqualTo(1);
        }
        AccountingEvent exhausted =
                accountingEventRepository.findById(held.getEventId()).orElseThrow();
        assertThat(exhausted.getAttemptCount()).isEqualTo(3);
        assertThat(exhausted.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(exhausted.getFailureReasonCode()).isEqualTo("INVOICE_NOT_FOUND");
        assertThat(retryJob.retryBoundTenant())
                .as("no longer a retry candidate")
                .isZero();

        seedInvoice("115.00", customerId);
        assertThat(retryJob.retryBoundTenant())
                .as("the cap still holds after the invoice arrives")
                .isZero();
        AccountingEventResponse reprocessed = inTransaction(
                () -> eventIngestionService.reprocessEvent(held.getEventId(), new ReprocessEventRequest(), "ops-user"));
        assertThat(reprocessed.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(singleApplication().getApplicationTimestamp()).isEqualTo(SETTLED_AT);
    }

    // ===== undo across paths (review #2550, BR-8) =====

    @Test
    @DisplayName("BR-8 across paths: settled payment applied automatically, undone, then a late INVOICE_PAYMENT for it"
            + " is DUPLICATE_IGNORED and applies or credits nothing")
    void undoSticks_settledThenInvoicePayment() {
        seedInvoice("115.00", customerId);
        UUID intent = nextUuid();
        consume(nextUuid(), fact(intent, "CARD", "115.00"));
        paymentApplicationService.reversePaymentApplication(
                singleApplication().getPaymentApplicationId(), "Customer asked to keep it on account");

        UUID eventId = submitInvoicePayment(intent, "115.00");
        assertThat(drainer.drainBoundTenant()).isEqualTo(1);

        AccountingEvent event = accountingEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(event.getIdempotencyOutcome()).isEqualTo("DUPLICATE_IGNORED");
        assertThat(paymentApplicationRepository.count()).isEqualTo(1);
        assertThat(customerCreditRepository.count()).isZero();
        assertThat(receivablePaymentRepository.findById(intent).orElseThrow().getStatus())
                .isEqualTo(ReceivablePaymentStatus.AVAILABLE);
    }

    @Test
    @DisplayName("BR-8 across paths: settled fact held in a closed period, INVOICE_PAYMENT applies it, a clerk undoes"
            + " it; reprocessing the held row after reopening, or a re-publish, applies nothing")
    void undoSticks_invoicePaymentThenSettledReprocess() {
        seedInvoice("115.00", customerId);
        AccountingPeriod september = closedPeriod("2026-09");
        UUID intent = nextUuid();
        consume(nextUuid(), fact(intent, "CARD", "115.00"));
        AccountingEvent held = settledRows().getFirst();
        assertThat(held.getFailureReasonCode()).isEqualTo("PERIOD_CLOSED");

        submitInvoicePayment(intent, "115.00");
        assertThat(drainer.drainBoundTenant()).isEqualTo(1);
        PaymentApplication byInvoicePayment = singleApplication();
        assertThat(byInvoicePayment.getApplicationSource()).isEqualTo(ApplicationSource.INVOICE_PAYMENT);
        paymentApplicationService.reversePaymentApplication(
                byInvoicePayment.getPaymentApplicationId(), "Applied to the wrong job, clerk will match it");

        september.setStatus(AccountingPeriodStatus.OPEN);
        periodRepository.save(september);
        AccountingEventResponse reprocessed = inTransaction(
                () -> eventIngestionService.reprocessEvent(held.getEventId(), new ReprocessEventRequest(), "ops-user"));
        consume(nextUuid(), fact(intent, "CARD", "115.00"));

        assertThat(reprocessed.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(paymentApplicationRepository.count()).isEqualTo(1);
        assertThat(customerCreditRepository.count()).isZero();
        assertThat(receivablePaymentRepository.findById(intent).orElseThrow().getStatus())
                .isEqualTo(ReceivablePaymentStatus.AVAILABLE);
        assertThat(settledRows()).hasSize(1);
    }

    // ===== case g (Accounting Domain ruling, merge condition) =====

    @Test
    @DisplayName("g: a settled payment against an invoice with no open balance applies nothing, credits nothing,"
            + " posts nothing, stays AVAILABLE, and its row is SKIPPED / NOT_POSTABLE naming the invoice; a re-publish"
            + " writes no second row")
    void caseG_paidInvoiceLeftForAPerson() {
        seedInvoice("115.00", customerId);
        UUID first = nextUuid();
        consume(nextUuid(), fact(first, "CARD", "115.00"));
        drainOutbox();
        assertThat(balanceDue()).isEqualByComparingTo("0.00");
        long entriesBefore = journalEntryRepository.count();
        long outboxBefore = outboxRepository.count();

        UUID duplicate = nextUuid();
        consume(nextUuid(), fact(duplicate, "CARD", "115.00"));
        consume(nextUuid(), fact(duplicate, "CARD", "115.00")); // re-published under a new event id

        assertThat(paymentApplicationRepository.findAll())
                .extracting(PaymentApplication::getPaymentId)
                .containsExactly(first);
        assertThat(customerCreditRepository.count()).isZero();
        assertThat(outboxRepository.count()).isEqualTo(outboxBefore);
        drainOutbox();
        assertThat(journalEntryRepository.count()).isEqualTo(entriesBefore);
        assertThat(receivablePaymentRepository.findById(duplicate).orElseThrow().getStatus())
                .isEqualTo(ReceivablePaymentStatus.AVAILABLE);
        assertThat(settledRows()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(AccountingEventStatus.SKIPPED);
            assertThat(row.getFailureReasonCode()).isEqualTo("NOT_POSTABLE");
            assertThat(row.getErrorMessage()).isEqualTo("invoice INV-1 has no open balance; left for a person");
            assertThat(row.getDomainKeyId()).isEqualTo(duplicate.toString());
        });
    }

    // ===== criterion 9: unresolved references =====

    @Test
    @DisplayName("9: an application whose invoice and customer are in neither replica shows null display fields,"
            + " never a UUID")
    void criterion9_unresolvedDisplayFieldsAreNull() {
        UUID stranger = nextUuid();
        ReceivablePayment payment = new ReceivablePayment();
        payment.setPaymentId(nextUuid());
        payment.setCustomerId(stranger);
        payment.setCurrency("USD");
        payment.setTotalAmount(new BigDecimal("40.00"));
        payment.setUnappliedAmount(BigDecimal.ZERO);
        payment.setStatus(ReceivablePaymentStatus.FULLY_APPLIED);
        payment.setClearedAt(SETTLED_AT);
        payment.setSourceEventId(nextUuid());
        payment.setCreatedBy("it");
        ReceivablePayment saved = receivablePaymentRepository.save(payment);
        PaymentApplication application = new PaymentApplication();
        application.setPayment(saved);
        application.setInvoiceId(nextUuid());
        application.setCustomerId(stranger);
        application.setCurrency("USD");
        application.setAppliedAmount(new BigDecimal("40.00"));
        application.setApplicationTimestamp(SETTLED_AT);
        application.setApplicationRequestId("PAYMENT_SETTLED:" + saved.getPaymentId());
        application.setApplicationSource(ApplicationSource.PAYMENT_SETTLED);
        application.setCreatedAt(SETTLED_AT);
        application.setCreatedBy("SYSTEM");
        paymentApplicationRepository.save(application);

        AutomaticPaymentApplicationRow row = onlyRow(true);

        assertThat(row.getInvoiceNumber()).isNull();
        assertThat(row.getCustomerDisplayName()).isNull();
        assertThat(row.getCustomerReference()).isNull();
        assertThat(row.getActions()).containsExactly("UNDO");
    }

    // ===== criteria 10 and 11 =====

    @Test
    @DisplayName("11: ON_ACCOUNT is not applied and the row is SKIPPED / NOT_POSTABLE")
    void criterion11_onAccount() {
        seedInvoice("115.00", customerId);
        UUID intent = nextUuid();

        consume(nextUuid(), fact(intent, "ON_ACCOUNT", "115.00"));

        assertThat(paymentApplicationRepository.count()).isZero();
        assertThat(settledRows()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(AccountingEventStatus.SKIPPED);
            assertThat(row.getFailureReasonCode()).isEqualTo("NOT_POSTABLE");
            assertThat(row.getErrorMessage()).isEqualTo("method ON_ACCOUNT is not applied automatically");
        });
    }

    // ===== criterion 12 =====

    @Test
    @DisplayName("12: the migration marks INVOICE_PAYMENT: applications INVOICE_PAYMENT and all others MANUAL, in"
            + " every tenant")
    void criterion12_backfill() {
        DataSource isolated = AccountingPostgresContainer.ownerDataSource("payment-application-source-backfill");
        JdbcTemplate jdbc = new JdbcTemplate(isolated);
        Flyway.configure()
                .dataSource(isolated)
                .locations("classpath:db/migration")
                .target("7")
                .load()
                .migrate();
        UUID otherTenant = UUID.fromString("00000000-0000-7000-8000-00000000b2b2");
        Map<UUID, String> expected = new HashMap<>();
        for (UUID tenant : List.of(TENANT, otherTenant)) {
            UUID payment = nextUuid();
            jdbc.update(
                    "INSERT INTO receivable_payment (tenant_id, payment_id, customer_id, currency, total_amount,"
                            + " unapplied_amount, cleared_at, created_at, status, version)"
                            + " VALUES (?, ?, ?, 'USD', 300, 0, TIMESTAMPTZ '2026-09-01 00:00:00+00',"
                            + " TIMESTAMPTZ '2026-09-01 00:00:00+00', 'FULLY_APPLIED', 0)",
                    tenant,
                    payment,
                    customerId);
            expected.put(insertApplication(jdbc, tenant, payment, "INVOICE_PAYMENT:" + nextUuid()), "INVOICE_PAYMENT");
            expected.put(
                    insertApplication(jdbc, tenant, payment, UUID.randomUUID().toString()), "MANUAL");
            expected.put(insertApplication(jdbc, tenant, payment, "invoice_payment-like"), "MANUAL");
        }

        Flyway.configure()
                .dataSource(isolated)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        Map<UUID, String> actual = new HashMap<>();
        jdbc.query("SELECT payment_application_id, application_source FROM payment_application", rs -> {
            actual.put(UUID.fromString(rs.getString(1)), rs.getString(2));
        });
        assertThat(actual).isEqualTo(expected);
        assertThat(jdbc.queryForObject(
                        "SELECT relforcerowsecurity FROM pg_class WHERE relname = 'payment_application'",
                        Boolean.class))
                .as("FORCE ROW LEVEL SECURITY is restored")
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_payment_application_source_ts'",
                        Long.class))
                .isEqualTo(1);
    }

    // ===== helpers =====

    private static UUID insertApplication(JdbcTemplate jdbc, UUID tenant, UUID payment, String requestId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO payment_application (tenant_id, payment_application_id, payment_id, invoice_id,"
                        + " customer_id, currency, applied_amount, application_timestamp, created_at, created_by,"
                        + " application_request_id) VALUES (?, ?, ?, ?, ?, 'USD', 100, TIMESTAMPTZ '2026-09-01 00:00:00+00',"
                        + " TIMESTAMPTZ '2026-09-01 00:00:00+00', 'test', ?)",
                tenant,
                id,
                payment,
                UUID.randomUUID(),
                UUID.randomUUID(),
                requestId);
        return id;
    }

    // ===== #2558: the tenant's accounting-calendar zone, with the clock in UTC =====

    /** 2026-01-31T23:30-06:00 in Chicago: still January there, already February in UTC (the clock's zone). */
    private static final Instant JAN_31_2330_CHICAGO = Instant.parse("2026-02-01T05:30:00Z");

    /** 2026-02-01T00:30-06:00 in Chicago. */
    private static final Instant FEB_01_0030_CHICAGO = Instant.parse("2026-02-01T06:30:00Z");

    @Test
    @DisplayName("#2558 AC1: in a Chicago calendar a settlement at 2026-01-31T23:30-06:00 is applied and posted on"
            + " 2026-01-31, and the January period decides")
    void timeZone_ac1_lastEveningOfJanuaryStaysInJanuary() {
        assertThat(clock.getZone()).as("the clock bean stays UTC").isEqualTo(ZoneOffset.UTC);
        setZone("America/Chicago");
        seedInvoice("115.00", customerId);
        UUID intent = nextUuid();

        consume(nextUuid(), fact(intent, "CARD", "115.00", JAN_31_2330_CHICAGO));

        assertThat(singleApplication().getApplicationTimestamp()).isEqualTo(JAN_31_2330_CHICAGO);
        assertThat(settledRows()).isEmpty();
        drainOutbox();
        assertThat(postings()).containsExactly(new Posting("1090", "1200", "115.00", LocalDate.of(2026, 1, 31)));
        assertThat(periodRepository.findByPeriodCode("2026-01"))
                .as("posted into January")
                .isPresent();
        assertThat(periodRepository.findByPeriodCode("2026-02"))
                .as("never into February")
                .isEmpty();
    }

    @Test
    @DisplayName("#2558: the customer credit an excess becomes is issued on the same Chicago date, 2026-01-31")
    void timeZone_excessCreditIssuedInJanuary() {
        setZone("America/Chicago");
        seedInvoice("115.00", customerId);

        consume(nextUuid(), fact(nextUuid(), "CARD", "120.00", JAN_31_2330_CHICAGO));

        drainOutbox();
        assertThat(postings())
                .containsExactlyInAnyOrder(
                        new Posting("1090", "1200", "115.00", LocalDate.of(2026, 1, 31)),
                        new Posting("1090", "2300", "5.00", LocalDate.of(2026, 1, 31)));
    }

    @Test
    @DisplayName("#2558 AC2: with January closed that settlement is held SUSPENDED / PERIOD_CLOSED, not posted into"
            + " February")
    void timeZone_ac2_closedJanuaryHoldsIt() {
        setZone("America/Chicago");
        seedInvoice("115.00", customerId);
        closedPeriod("2026-01");

        consume(nextUuid(), fact(nextUuid(), "CARD", "115.00", JAN_31_2330_CHICAGO));

        assertThat(paymentApplicationRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        AccountingEvent held = settledRows().getFirst();
        assertThat(held.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(held.getFailureReasonCode()).isEqualTo("PERIOD_CLOSED");
        assertThat(held.getFailureDetails()).contains("2026-01-31");
        assertThat(held.getTransactionDate()).isEqualTo(LocalDateTime.of(2026, 1, 31, 23, 30));
        assertThat(journalEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("#2558 AC3: a settlement at 2026-02-01T00:30-06:00 dates into February, even with January closed")
    void timeZone_ac3_firstMinutesOfFebruary() {
        setZone("America/Chicago");
        seedInvoice("115.00", customerId);
        closedPeriod("2026-01");

        consume(nextUuid(), fact(nextUuid(), "CARD", "115.00", FEB_01_0030_CHICAGO));

        assertThat(singleApplication().getApplicationTimestamp()).isEqualTo(FEB_01_0030_CHICAGO);
        drainOutbox();
        assertThat(postings()).containsExactly(new Posting("1090", "1200", "115.00", LocalDate.of(2026, 2, 1)));
    }

    @Test
    @DisplayName("#2558: without an ACCOUNTING_TIME_ZONE row the settlement is held SUSPENDED /"
            + " ACCOUNTING_TIME_ZONE_UNSET (never dated in UTC), and the retry job applies it once the zone exists")
    void timeZone_unsetHoldsTheSettlement() {
        removeZone();
        seedInvoice("115.00", customerId);

        consume(nextUuid(), fact(nextUuid(), "CARD", "115.00", JAN_31_2330_CHICAGO));

        assertThat(paymentApplicationRepository.count()).isZero();
        AccountingEvent held = settledRows().getFirst();
        assertThat(held.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(held.getFailureReasonCode()).isEqualTo("ACCOUNTING_TIME_ZONE_UNSET");

        // Provisioning seeds the row late (the startup sweep), or an administrator sets it: the retry job then
        // releases the hold by itself, no reprocess needed.
        setZone("America/Chicago");
        retryJob.retryBoundTenant();

        assertThat(accountingEventRepository
                        .findById(held.getEventId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(AccountingEventStatus.PROCESSED);
        drainOutbox();
        assertThat(postings()).containsExactly(new Posting("1090", "1200", "115.00", LocalDate.of(2026, 1, 31)));
    }

    @Test
    @DisplayName("#2558: a zone change never re-cuts history; what was posted in UTC keeps its date, the next"
            + " settlement is dated in the new zone")
    void timeZone_changeKeepsPostedDates() {
        seedInvoice("300.00", customerId);
        consume(nextUuid(), fact(nextUuid(), "CARD", "115.00", JAN_31_2330_CHICAGO));
        drainOutbox();
        assertThat(postings()).containsExactly(new Posting("1090", "1200", "115.00", LocalDate.of(2026, 2, 1)));

        inTransaction(() -> configurationService.setAccountingTimeZone("America/Chicago"));
        assertThat(postings())
                .as("the posted entry keeps its date")
                .containsExactly(new Posting("1090", "1200", "115.00", LocalDate.of(2026, 2, 1)));

        outboxRepository.deleteAll();
        consume(nextUuid(), fact(nextUuid(), "CARD", "100.00", JAN_31_2330_CHICAGO));
        drainOutbox();
        assertThat(postings())
                .containsExactlyInAnyOrder(
                        new Posting("1090", "1200", "115.00", LocalDate.of(2026, 2, 1)),
                        new Posting("1090", "1200", "100.00", LocalDate.of(2026, 1, 31)));
    }

    private void setZone(String zone) {
        AccountingConfiguration row = configurationRepository
                .findByConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY)
                .orElseGet(() -> {
                    AccountingConfiguration created = new AccountingConfiguration();
                    created.setConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY);
                    return created;
                });
        row.setConfigValue(zone);
        configurationRepository.save(row);
    }

    private void removeZone() {
        configurationRepository
                .findByConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY)
                .ifPresent(configurationRepository::delete);
    }

    private void consume(UUID eventId, PaymentSettledV1 fact) {
        listener.onPaymentEvent(envelopeMapper.writeValueAsString(
                Map.of("eventType", PaymentSettledV1.EVENT_TYPE, "eventId", eventId.toString(), "payload", fact)));
    }

    private PaymentSettledV1 fact(UUID intent, String method, String amount) {
        return fact(intent, method, amount, SETTLED_AT);
    }

    private PaymentSettledV1 fact(UUID intent, String method, String amount, Instant settledAt) {
        return new PaymentSettledV1(
                intent,
                invoiceId,
                "INV-1",
                null,
                null,
                customerId.toString(),
                method,
                new BigDecimal(amount),
                "USD",
                "stripe",
                "txn_" + intent,
                settledAt);
    }

    private void seedInvoice(String total, UUID party) {
        extInvoiceRepository.save(ExtInvoice.builder()
                .invoiceId(invoiceId)
                .invoiceNumber("INV-1")
                .workorderId(nextUuid())
                .partyId(party.toString())
                .status("FINALIZED")
                .total(new BigDecimal(total))
                .invoiceCreatedAt(Instant.parse("2026-09-10T00:00:00Z"))
                .finalizedAt(Instant.parse("2026-09-10T00:00:00Z"))
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-09-10T00:00:00Z"))
                .build());
    }

    private AccountingPeriod closedPeriod(String code) {
        // Posting provisions a period row on first use, so close the existing one when there is one.
        AccountingPeriod period = periodRepository.findByPeriodCode(code).orElseGet(AccountingPeriod::new);
        period.setPeriodCode(code);
        period.setStartDate(LocalDate.parse(code + "-01"));
        period.setEndDate(LocalDate.parse(code + "-01").plusMonths(1).minusDays(1));
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosedAt(Instant.parse("2026-10-01T05:00:00Z"));
        period.setClosedBy("it-controller");
        return periodRepository.save(period);
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
        request.put("sourceSystem", "IT_2503");
        request.put("payload", payload);
        return eventIngestionService.submitEvent(request).getEventId();
    }

    private BigDecimal balanceDue() {
        return inTransaction(() -> {
            ExtInvoice invoice = extInvoiceRepository.findById(invoiceId).orElseThrow();
            return invoice.getTotal()
                    .subtract(paymentApplicationRepository.sumAppliedAmountByInvoiceId(invoiceId))
                    .add(reversalRepository.sumReversedAmountByInvoiceId(invoiceId));
        });
    }

    private PaymentApplication singleApplication() {
        List<PaymentApplication> applications = paymentApplicationRepository.findAll();
        assertThat(applications).hasSize(1);
        return applications.getFirst();
    }

    private List<AccountingEvent> settledRows() {
        return accountingEventRepository.findAll().stream()
                .filter(event -> PaymentSettledV1.EVENT_TYPE.equals(event.getEventType()))
                .toList();
    }

    private AutomaticPaymentApplicationRow onlyRow(boolean canUndo) {
        AutomaticPaymentApplicationsPage page =
                automaticQueryService.listAutomatic(Instant.parse("2026-09-15T00:00:00Z"), 0, 50, canUndo);
        assertThat(page.getTotalElements()).isEqualTo(1);
        return page.getItems().getFirst();
    }

    private LocalDate settledDate() {
        return zoneResolver.postingDate(SETTLED_AT);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void drainOutbox() {
        for (EventOutbox outbox : outboxRepository.findAll()) {
            try {
                String type = outbox.getEventType();
                if (PaymentApplicationGLPostingEvent.class.getName().equals(type)) {
                    cashReceiptHandler.onPaymentApplicationGLPosting(
                            objectMapper.readValue(outbox.getPayload(), PaymentApplicationGLPostingEvent.class));
                } else if (CustomerCreditIssuanceGLPostingEvent.class.getName().equals(type)) {
                    issuanceHandler.onCustomerCreditIssuanceGLPosting(
                            objectMapper.readValue(outbox.getPayload(), CustomerCreditIssuanceGLPostingEvent.class));
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Failed to deserialize outbox payload", e);
            }
        }
    }

    /** One two-line journal entry: debit account, credit account, amount and date. */
    private record Posting(String debit, String credit, String amount, LocalDate date) {}

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
                LocalDateTime date = entry.getTransactionDate();
                postings.add(new Posting(
                        debit,
                        credit,
                        amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                        date.toLocalDate()));
            }
            return postings;
        });
    }
}
