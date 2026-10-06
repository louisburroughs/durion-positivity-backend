package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.PostgresCommittingTestBase;
import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.CustomerCredit;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.EventOutboxRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End to end on Postgres (#2435): an {@code INVOICE_PAYMENT} event submitted through the ingestion
 * service stays {@code RECEIVED} until the drainer runs, then lands in the AR subledger — a
 * {@code ReceivablePayment} keyed on the Payment-domain {@code paymentId}, an application against
 * the invoice, and the application's GL work item in the outbox — with no journal entry of the
 * event's own. Exercises the drainer's SKIP LOCKED claim query on a real database.
 *
 * <p>Commits, so it has a database of its own; the drainer bean is switched on here (the {@code
 * pg} profile turns it off) with its scheduled poll pushed out of the way, and the test calls one
 * tenant pass directly.
 */
@Import(TestSecurityConfig.class)
@DisplayName("INVOICE_PAYMENT drained to the AR subledger (#2435)")
class InvoicePaymentEventDrainIT extends PostgresCommittingTestBase {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, "invoice-payment-drain");
        registerCommonProperties(registry);
        registry.add("pos.accounting.event-drainer.enabled", () -> "true");
        registry.add("pos.accounting.event-drainer.initial-delay-ms", () -> "3600000");
    }

    private static final AtomicInteger UUID_COUNTER = new AtomicInteger(0x2435);

    private static UUID nextUuid() {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012x", UUID_COUNTER.getAndIncrement()));
    }

    /** Keeps outbox dispatch off: the test asserts the work item, not its asynchronous posting. */
    @MockitoBean
    private OutboxProcessor outboxProcessor;

    @Autowired
    private EventIngestionService eventIngestionService;

    @Autowired
    private ReceivedAccountingEventDrainer drainer;

    @Autowired
    private AccountingEventRepository accountingEventRepository;

    @Autowired
    private ReprocessingAttemptHistoryRepository reprocessingAttemptHistoryRepository;

    @Autowired
    private ReceivablePaymentRepository receivablePaymentRepository;

    @Autowired
    private PaymentApplicationService paymentApplicationService;

    @Autowired
    private PaymentApplicationRepository paymentApplicationRepository;

    @Autowired
    private CustomerCreditRepository customerCreditRepository;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private AccountingSequenceRepository sequenceRepository;

    private UUID customerId;
    private UUID invoiceId;

    @BeforeEach
    void setUp() {
        clear();
        customerId = nextUuid();
        invoiceId = nextUuid();
        extInvoiceRepository.save(ExtInvoice.builder()
                .invoiceId(invoiceId)
                .workorderId(nextUuid())
                .partyId(customerId.toString())
                .status("FINALIZED")
                .total(new BigDecimal("150.00"))
                .finalizedAt(Instant.parse("2026-09-01T00:00:00Z"))
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-09-01T00:00:00Z"))
                .build());
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private void clear() {
        // Attempt history references accounting_event (see #2202); outbox and credits reference payments.
        reprocessingAttemptHistoryRepository.deleteAll();
        outboxRepository.deleteAll();
        customerCreditRepository.deleteAll();
        paymentApplicationRepository.deleteAll();
        receivablePaymentRepository.deleteAll();
        accountingEventRepository.deleteAll();
        extInvoiceRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        sequenceRepository.deleteAll();
    }

    @Test
    @DisplayName("a submitted payment is applied to its invoice through the subledger on the next drain")
    void submittedPayment_appliedOnDrain() {
        UUID paymentId = nextUuid();
        UUID eventId = submit(payload(paymentId, "100.00"));
        assertThat(status(eventId)).isEqualTo(AccountingEventStatus.RECEIVED);

        assertThat(drainer.drainBoundTenant()).isEqualTo(1);

        AccountingEvent event = accountingEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(event.getIdempotencyOutcome()).isEqualTo("NEW");
        assertThat(event.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(event.getJournalEntryId()).isNull();

        ReceivablePayment payment =
                receivablePaymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getCustomerId()).isEqualTo(customerId);
        assertThat(payment.getSourceEventId()).isEqualTo(eventId);
        assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("0");

        List<PaymentApplication> applications = paymentApplicationRepository.findAll();
        assertThat(applications).singleElement().satisfies(application -> {
            assertThat(application.getInvoiceId()).isEqualTo(invoiceId);
            assertThat(application.getAppliedAmount()).isEqualByComparingTo("100.00");
            assertThat(application.getApplicationRequestId()).isEqualTo("INVOICE_PAYMENT:" + eventId);
        });
        assertThat(outboxRepository.findAll())
                .extracting(EventOutbox::getAggregateType)
                .containsExactly("PaymentApplication");

        // Nothing left RECEIVED: the next pass is a no-op.
        assertThat(drainer.drainBoundTenant()).isZero();
    }

    @Test
    @DisplayName("a payment beyond the balance due, then one on a paid-in-full invoice, become customer credits")
    void overpaymentAndPaidInFull_becomeCustomerCredits() {
        UUID first = submit(payload(nextUuid(), "200.00"));
        assertThat(drainer.drainBoundTenant()).isEqualTo(1);
        assertThat(status(first)).isEqualTo(AccountingEventStatus.PROCESSED);

        UUID second = submit(payload(nextUuid(), "30.00"));
        assertThat(drainer.drainBoundTenant()).isEqualTo(1);
        assertThat(status(second)).isEqualTo(AccountingEventStatus.PROCESSED);

        assertThat(paymentApplicationRepository.findAll())
                .singleElement()
                .satisfies(application ->
                        assertThat(application.getAppliedAmount()).isEqualByComparingTo("150.00"));
        assertThat(customerCreditRepository.findAll())
                .extracting(CustomerCredit::getAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("50.00"), new BigDecimal("30.00"));
        assertThat(outboxRepository.findAll())
                .extracting(EventOutbox::getAggregateType)
                .containsExactlyInAnyOrder("PaymentApplication", "CustomerCreditIssuance", "CustomerCreditIssuance");
    }

    @Test
    @DisplayName("a payment a settlement fact recorded first is applied, and a late settlement fact reuses it")
    void settlementFirst_thenEvent_appliesOnce() {
        UUID paymentId = nextUuid();
        UUID recordingEventId = nextUuid();
        paymentApplicationService.handlePaymentCleared(
                paymentId,
                customerId,
                "USD",
                new BigDecimal("100.00"),
                Instant.parse("2026-09-15T10:00:00Z"),
                recordingEventId,
                null,
                "CARD");

        UUID eventId = submit(payload(paymentId, "100.00"));
        assertThat(drainer.drainBoundTenant()).isEqualTo(1);

        assertThat(status(eventId)).isEqualTo(AccountingEventStatus.PROCESSED);
        ReceivablePayment payment =
                receivablePaymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getSourceEventId()).isEqualTo(recordingEventId);
        assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("0");
        assertThat(paymentApplicationRepository.findAll())
                .singleElement()
                .satisfies(application ->
                        assertThat(application.getAppliedAmount()).isEqualByComparingTo("100.00"));

        // The other order: a settlement fact arriving after the event reuses the recorded payment.
        ReceivablePayment reused = paymentApplicationService.handlePaymentCleared(
                paymentId, customerId, "USD", new BigDecimal("100.00"), Instant.now(), nextUuid(), null, null);
        assertThat(reused.getPaymentId()).isEqualTo(paymentId);
        assertThat(receivablePaymentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the legacy payload without paymentId fails INVALID_PAYLOAD and writes nothing")
    void legacyPayload_failsWithoutWriting() {
        Map<String, Object> legacy = new HashMap<>();
        legacy.put("invoiceId", invoiceId.toString());
        legacy.put("paymentMethod", "CREDIT_CARD");
        legacy.put("amountPaid", 100);
        UUID eventId = submit(legacy);

        assertThat(drainer.drainBoundTenant()).isEqualTo(1);

        AccountingEvent event = accountingEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(AccountingEventStatus.FAILED);
        assertThat(event.getFailureReasonCode()).isEqualTo("INVALID_PAYLOAD");
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(receivablePaymentRepository.findAll()).isEmpty();
        assertThat(paymentApplicationRepository.findAll()).isEmpty();
        assertThat(outboxRepository.findAll()).isEmpty();
    }

    private UUID submit(Map<String, Object> payload) {
        Map<String, Object> request = new HashMap<>();
        request.put("eventType", InvoicePaymentEventProcessor.EVENT_TYPE);
        request.put("organizationId", nextUuid());
        request.put("sourceSystem", "IT_2435");
        request.put("payload", payload);
        AccountingEventResponse response = eventIngestionService.submitEvent(request);
        return response.getEventId();
    }

    private AccountingEventStatus status(UUID eventId) {
        return accountingEventRepository.findById(eventId).orElseThrow().getStatus();
    }

    private Map<String, Object> payload(UUID paymentId, String amount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentId", paymentId.toString());
        payload.put("invoiceId", invoiceId.toString());
        payload.put("amountPaid", new BigDecimal(amount));
        payload.put("currency", "USD");
        payload.put("paidAt", "2026-09-15T10:00:00Z");
        payload.put("paymentMethod", "CREDIT_CARD");
        return payload;
    }
}
