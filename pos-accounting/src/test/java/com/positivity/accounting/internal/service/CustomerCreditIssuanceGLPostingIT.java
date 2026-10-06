package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.CustomerCreditIssuanceGLPostingEvent;
import com.positivity.accounting.internal.dto.CustomerCreditRefundRequest;
import com.positivity.accounting.internal.dto.CustomerCreditReliefGLPostingEvent;
import com.positivity.accounting.internal.dto.PaymentApplicationGLPostingEvent;
import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.dto.RemainderCreditRequest;
import com.positivity.accounting.internal.dto.RemainderCreditResponse;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.PaymentNotAvailableException;
import com.positivity.accounting.internal.exception.PaymentRemainderChangedException;
import com.positivity.accounting.internal.handler.CustomerCreditIssuanceGLPostingEventHandler;
import com.positivity.accounting.internal.handler.CustomerCreditReliefGLPostingEventHandler;
import com.positivity.accounting.internal.handler.PaymentApplicationGLPostingEventHandler;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.CustomerCreditTransactionRepository;
import com.positivity.accounting.internal.repository.EventOutboxRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-Postgres IT for customer-credit issuance GL posting (parity-C1, issue
 * #975). Runs the full Flyway chain + repeatable seed (which now carries the
 * {@code CUSTOMER_CREDIT_ISSUANCE} posting category and its two mapping keys)
 * on a Testcontainers Postgres, so the seed correctness and the
 * {@link com.positivity.accounting.internal.service.GLMappingResolver} account
 * resolution are exercised end-to-end — H2 does not enforce the Postgres
 * CHECK/FK constraints the seed relies on.
 *
 * <p>Exercises the overpayment path: applying more than an invoice's balance
 * records a {@code CustomerCredit} for the excess and enqueues both GL legs
 * (AR cash receipt + credit issuance). Both work items are then drained from the
 * outbox and posted, and the ledger is asserted balanced across both entries.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Customer credit issuance GL posting (parity-C1 #975, real Postgres)")
class CustomerCreditIssuanceGLPostingIT {

    /**
     * A database of this IT's own inside the shared container: it commits its fixtures and clears
     * whole tables, so it must not share the default database with tests that read the seed.
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, "customer-credit-issuance");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Schema + seed come from the real Flyway chain, not Hibernate DDL.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private PaymentApplicationServiceImpl paymentApplicationService;

    @Autowired
    private CustomerCreditService customerCreditService;

    /** The {@code @Primary} retrying decorator, the bean the controller calls. */
    @Autowired
    private PaymentApplicationService retryingPaymentApplicationService;

    @Autowired
    private CustomerCreditReliefGLPostingEventHandler reliefHandler;

    @Autowired
    private CustomerCreditTransactionRepository creditTransactionRepository;

    @Autowired
    private PaymentApplicationGLPostingEventHandler cashReceiptHandler;

    @Autowired
    private CustomerCreditIssuanceGLPostingEventHandler issuanceHandler;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private ReceivablePaymentRepository receivablePaymentRepository;

    @Autowired
    private PaymentApplicationRepository paymentApplicationRepository;

    @Autowired
    private CustomerCreditRepository customerCreditRepository;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private AccountingSequenceRepository sequenceRepository;

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * The real bean's 5-second poll would dispatch PENDING outbox rows concurrently with the
     * deterministic {@link #drainOutbox()} calls and the {@code @AfterEach} cleanup, leaking
     * another test's GL postings into this one's account sums. Mocking it keeps every dispatch
     * on the test thread.
     */
    @MockitoBean
    private OutboxProcessor outboxProcessor;

    @AfterEach
    void cleanUp() {
        journalEntryRepository.deleteAll();
        sequenceRepository.deleteAll();
        outboxRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        creditTransactionRepository.deleteAll();
        customerCreditRepository.deleteAll();
        paymentApplicationRepository.deleteAll();
        receivablePaymentRepository.deleteAll();
        extInvoiceRepository.deleteAll();
    }

    @Test
    @DisplayName("AC-5: overpayment posts Dr Undeposited Funds=X across both legs; Cr AR=Y; Cr Credit Liability=X-Y")
    void overpayment_postsBalancedCashReceiptAndCreditIssuance() {
        // Payment X=1000 applied against a Y=400 invoice -> 400 applied, 600 excess.
        BigDecimal paymentTotal = new BigDecimal("1000.00");
        BigDecimal invoiceDue = new BigDecimal("400.00");
        BigDecimal expectedCredit = new BigDecimal("600.00");

        UUID invoiceId = seedFinalizedInvoice(invoiceDue);
        UUID paymentId = seedAvailablePayment(paymentTotal);
        String requestId = UUID.randomUUID().toString();

        paymentApplicationService.applyPaymentToInvoices(paymentId, request(requestId, invoiceId, paymentTotal));

        // AC-1: both GL legs enqueued in the same transaction as the application/credit.
        List<EventOutbox> outbox = outboxRepository.findAll();
        assertThat(outbox)
                .extracting(EventOutbox::getEventType)
                .containsExactlyInAnyOrder(
                        PaymentApplicationGLPostingEvent.class.getName(),
                        CustomerCreditIssuanceGLPostingEvent.class.getName());

        // Credit recorded for the excess.
        assertThat(customerCreditRepository.findAll())
                .singleElement()
                .satisfies(credit -> assertThat(credit.getAmount()).isEqualByComparingTo(expectedCredit));

        // Drain both work items through their handlers (accounts resolved via the seed).
        drainOutbox();

        UUID undepositedFunds = accountId("1090");
        UUID accountsReceivable = accountId("1200");
        UUID creditLiability = accountId("2300");

        Map<UUID, BigDecimal> debits = new HashMap<>();
        Map<UUID, BigDecimal> credits = new HashMap<>();
        sumLines(debits, credits);

        // AC-5: total Dr Undeposited Funds across both entries = X.
        assertThat(debits.getOrDefault(undepositedFunds, BigDecimal.ZERO)).isEqualByComparingTo(paymentTotal);
        // Cr AR = Y (applied).
        assertThat(credits.getOrDefault(accountsReceivable, BigDecimal.ZERO)).isEqualByComparingTo(invoiceDue);
        // Cr Customer Credit Liability = X - Y (excess).
        assertThat(credits.getOrDefault(creditLiability, BigDecimal.ZERO)).isEqualByComparingTo(expectedCredit);

        // Two balanced POSTED entries in total.
        assertThat(journalEntryRepository.count()).isEqualTo(2);

        // AC-4: replaying the issuance work item is a no-op (no double-post).
        CustomerCreditIssuanceGLPostingEvent issuance = issuanceEventFromEnqueued();
        issuanceHandler.onCustomerCreditIssuanceGLPosting(issuance);
        assertThat(journalEntryRepository.count())
                .as("replay adds no second issuance entry")
                .isEqualTo(2);
        Map<UUID, BigDecimal> creditsAfterReplay = new HashMap<>();
        sumLines(new HashMap<>(), creditsAfterReplay);
        assertThat(creditsAfterReplay.getOrDefault(creditLiability, BigDecimal.ZERO))
                .isEqualByComparingTo(expectedCredit);
    }

    @Test
    @DisplayName("AC-6: exact application (no excess) posts no credit-issuance entry")
    void exactApplication_postsNoCreditIssuanceEntry() {
        BigDecimal paymentTotal = new BigDecimal("1000.00");
        BigDecimal applied = new BigDecimal("400.00");

        UUID invoiceId = seedFinalizedInvoice(new BigDecimal("400.00"));
        UUID paymentId = seedAvailablePayment(paymentTotal);
        String requestId = UUID.randomUUID().toString();

        // Apply exactly the balance due -> no excess, no credit.
        paymentApplicationService.applyPaymentToInvoices(paymentId, request(requestId, invoiceId, applied));

        // Only the AR cash-receipt leg is enqueued.
        assertThat(outboxRepository.findAll())
                .extracting(EventOutbox::getEventType)
                .containsExactly(PaymentApplicationGLPostingEvent.class.getName());
        assertThat(customerCreditRepository.count()).isZero();

        drainOutbox();

        // No line ever touches the customer-credit-liability account.
        Map<UUID, BigDecimal> debits = new HashMap<>();
        Map<UUID, BigDecimal> credits = new HashMap<>();
        sumLines(debits, credits);
        UUID creditLiability = accountId("2300");
        assertThat(credits.getOrDefault(creditLiability, BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(debits.getOrDefault(creditLiability, BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(journalEntryRepository.count()).isEqualTo(1);
    }

    // ===== Remainder credit (CAP:550 S35, #2524) =====

    @Test
    @DisplayName("#2524 AC8: crediting a 12.50 remainder issues one credit, posts Dr 1090 / Cr 2300, moves the payment"
            + " to FULLY_APPLIED, and a later refund posts Dr 2300 / Cr 1090")
    void remainderCredit_postsIssuanceAndLaterRefundRelief() {
        UUID invoiceId = seedFinalizedInvoice(new BigDecimal("100.00"));
        UUID paymentId = seedAvailablePayment(new BigDecimal("112.50"));
        paymentApplicationService.applyPaymentToInvoices(
                paymentId, request(UUID.randomUUID().toString(), invoiceId, new BigDecimal("100.00")));
        assertThat(receivablePaymentRepository.findById(paymentId).orElseThrow().getUnappliedAmount())
                .isEqualByComparingTo("12.50");

        RemainderCreditResponse response = paymentApplicationService.creditPaymentRemainder(
                paymentId, remainder("remainder-ac8", new BigDecimal("12.50")));

        assertThat(response.getAmount()).isEqualByComparingTo("12.50");
        assertThat(response.getRemainingAmount()).isEqualByComparingTo("0");
        assertThat(response.getCurrency()).isEqualTo("USD");
        assertThat(response.getPaymentId()).isEqualTo(paymentId);
        assertThat(response.getRequestId()).isEqualTo("remainder-ac8");
        ReceivablePayment payment =
                receivablePaymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(ReceivablePaymentStatus.FULLY_APPLIED);
        assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("0");
        assertThat(customerCreditRepository.findById(response.getCreditId()))
                .get()
                .satisfies(credit -> {
                    assertThat(credit.getAmount()).isEqualByComparingTo("12.50");
                    assertThat(credit.getRequestId()).isEqualTo("REMAINDER:remainder-ac8");
                    assertThat(credit.getSourcePaymentId()).isEqualTo(paymentId);
                });

        drainOutbox();

        UUID undepositedFunds = accountId("1090");
        UUID creditLiability = accountId("2300");
        Map<UUID, BigDecimal> debits = new HashMap<>();
        Map<UUID, BigDecimal> credits = new HashMap<>();
        sumLines(debits, credits);
        // Cash receipt: Dr 1090 100 / Cr 1200 100. Issuance: Dr 1090 12.50 / Cr 2300 12.50.
        assertThat(debits.getOrDefault(undepositedFunds, BigDecimal.ZERO)).isEqualByComparingTo("112.50");
        assertThat(credits.getOrDefault(creditLiability, BigDecimal.ZERO)).isEqualByComparingTo("12.50");
        assertThat(journalEntryRepository.count()).isEqualTo(2);

        // The refund path on that creditId (EXISTING refundCustomerCredit): Dr 2300 / Cr 1090.
        customerCreditService.refundCredit(
                response.getCreditId(),
                CustomerCreditRefundRequest.builder()
                        .requestId("refund-ac8")
                        .amount(new BigDecimal("12.50"))
                        .build(),
                "s35-it");
        drainOutbox();
        Map<UUID, BigDecimal> debitsAfterRefund = new HashMap<>();
        Map<UUID, BigDecimal> creditsAfterRefund = new HashMap<>();
        sumLines(debitsAfterRefund, creditsAfterRefund);
        assertThat(debitsAfterRefund.getOrDefault(creditLiability, BigDecimal.ZERO))
                .isEqualByComparingTo("12.50");
        assertThat(creditsAfterRefund.getOrDefault(undepositedFunds, BigDecimal.ZERO))
                .isEqualByComparingTo("12.50");
        assertThat(journalEntryRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("#2524 AC9: a replay returns the same credit and writes nothing; the requestId on another payment is"
            + " IDEMPOTENCY_CONFLICT; an apply key equal to a remainder key posts both issuance entries; an apply"
            + " replay returns its customerCredit")
    void remainderCredit_idempotencyAndNamespacing() {
        // An apply whose key equals the remainder key below, overpaying so it issues a credit.
        UUID invoiceId = seedFinalizedInvoice(new BigDecimal("40.00"));
        UUID overpaid = seedAvailablePayment(new BigDecimal("100.00"));
        PaymentApplicationResponse applied = paymentApplicationService.applyPaymentToInvoices(
                overpaid, request("shared-key", invoiceId, new BigDecimal("100.00")));
        assertThat(applied.getCustomerCredit()).isNotNull();

        // A payment with nothing to apply, credited whole under the same key.
        UUID idle = seedAvailablePayment(new BigDecimal("12.50"));
        RemainderCreditResponse first = paymentApplicationService.creditPaymentRemainder(
                idle, remainder("shared-key", new BigDecimal("12.50")));
        long creditsAfterFirst = customerCreditRepository.count();
        long outboxAfterFirst = outboxRepository.count();

        // Replay: same credit, nothing written.
        RemainderCreditResponse replay = paymentApplicationService.creditPaymentRemainder(
                idle, remainder("shared-key", new BigDecimal("12.50")));
        assertThat(replay.getCreditId()).isEqualTo(first.getCreditId());
        assertThat(customerCreditRepository.count()).isEqualTo(creditsAfterFirst);
        assertThat(outboxRepository.count()).isEqualTo(outboxAfterFirst);

        // The same requestId on another payment is refused.
        UUID other = seedAvailablePayment(new BigDecimal("5.00"));
        assertThatThrownBy(() -> paymentApplicationService.creditPaymentRemainder(
                        other, remainder("shared-key", new BigDecimal("5.00"))))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(receivablePaymentRepository.findById(other).orElseThrow().getStatus())
                .isEqualTo(ReceivablePaymentStatus.AVAILABLE);

        // Both issuance work items post: the keys are namespaced (APPLY: vs REMAINDER:).
        drainOutbox();
        UUID creditLiability = accountId("2300");
        Map<UUID, BigDecimal> credits = new HashMap<>();
        sumLines(new HashMap<>(), credits);
        assertThat(credits.getOrDefault(creditLiability, BigDecimal.ZERO)).isEqualByComparingTo("72.50"); // 60 + 12.50
        assertThat(journalEntryRepository.count()).isEqualTo(3); // cash receipt + two issuances

        // An apply replay returns the credit it issued.
        PaymentApplicationResponse appliedReplay = paymentApplicationService.applyPaymentToInvoices(
                overpaid, request("shared-key", invoiceId, new BigDecimal("100.00")));
        assertThat(appliedReplay.getCustomerCredit()).isNotNull();
        assertThat(appliedReplay.getCustomerCredit().getCreditId())
                .isEqualTo(applied.getCustomerCredit().getCreditId());
        assertThat(customerCreditRepository.findById(applied.getCustomerCredit().getCreditId()))
                .get()
                .satisfies(credit -> assertThat(credit.getRequestId()).isEqualTo("APPLY:shared-key"));
    }

    @Test
    @DisplayName("#2524 AC10: a stale expectedAmount is PAYMENT_REMAINDER_CHANGED and a FULLY_APPLIED payment"
            + " PAYMENT_NOT_AVAILABLE; nothing is written")
    void remainderCredit_refusals() {
        UUID paymentId = seedAvailablePayment(new BigDecimal("12.50"));

        assertThatThrownBy(() -> paymentApplicationService.creditPaymentRemainder(
                        paymentId, remainder("stale", new BigDecimal("10.00"))))
                .isInstanceOf(PaymentRemainderChangedException.class);
        assertThat(customerCreditRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();

        paymentApplicationService.creditPaymentRemainder(paymentId, remainder("fresh", new BigDecimal("12.50")));
        assertThatThrownBy(() -> paymentApplicationService.creditPaymentRemainder(
                        paymentId, remainder("again", new BigDecimal("12.50"))))
                .isInstanceOf(PaymentNotAvailableException.class);
        assertThat(customerCreditRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("#2524 AC9 under concurrency: two simultaneous requests with the same requestId both return the same"
            + " credit, and exactly one credit and one issuance work item exist")
    void remainderCredit_concurrentSameRequestId_replaysTheWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID paymentId = seedAvailablePayment(new BigDecimal("12.50"));
            String requestId = "race-" + round + "-" + UUID.randomUUID();
            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Callable<RemainderCreditResponse> call = () -> {
                    start.await();
                    return retryingPaymentApplicationService.creditPaymentRemainder(
                            paymentId, remainder(requestId, new BigDecimal("12.50")));
                };
                java.util.concurrent.Future<RemainderCreditResponse> first = pool.submit(call);
                java.util.concurrent.Future<RemainderCreditResponse> second = pool.submit(call);
                RemainderCreditResponse a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
                RemainderCreditResponse b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);

                assertThat(a.getCreditId())
                        .as("round %s: both callers get the same credit", round)
                        .isEqualTo(b.getCreditId());
                assertThat(customerCreditRepository.findBySourcePaymentId(paymentId))
                        .as("round %s: one credit row", round)
                        .hasSize(1);
                assertThat(outboxRepository.findAll().stream()
                                .filter(o -> CustomerCreditIssuanceGLPostingEvent.class
                                        .getName()
                                        .equals(o.getEventType()))
                                .filter(o -> o.getPayload().contains("REMAINDER:" + requestId))
                                .count())
                        .as("round %s: one issuance work item", round)
                        .isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static RemainderCreditRequest remainder(String requestId, BigDecimal expectedAmount) {
        return RemainderCreditRequest.builder()
                .requestId(requestId)
                .expectedAmount(expectedAmount)
                .build();
    }

    // ===== helpers =====

    /** Drain all pending outbox work items through their handlers (deterministic, no scheduler). */
    private void drainOutbox() {
        for (EventOutbox outbox : outboxRepository.findAll()) {
            dispatch(outbox);
        }
    }

    private void dispatch(EventOutbox outbox) {
        try {
            String type = outbox.getEventType();
            if (PaymentApplicationGLPostingEvent.class.getName().equals(type)) {
                cashReceiptHandler.onPaymentApplicationGLPosting(
                        objectMapper.readValue(outbox.getPayload(), PaymentApplicationGLPostingEvent.class));
            } else if (CustomerCreditIssuanceGLPostingEvent.class.getName().equals(type)) {
                issuanceHandler.onCustomerCreditIssuanceGLPosting(
                        objectMapper.readValue(outbox.getPayload(), CustomerCreditIssuanceGLPostingEvent.class));
            } else if (CustomerCreditReliefGLPostingEvent.class.getName().equals(type)) {
                reliefHandler.onCustomerCreditReliefGLPosting(
                        objectMapper.readValue(outbox.getPayload(), CustomerCreditReliefGLPostingEvent.class));
            } else {
                throw new IllegalStateException("Unexpected outbox event type: " + type);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize outbox payload", e);
        }
    }

    private CustomerCreditIssuanceGLPostingEvent issuanceEventFromEnqueued() {
        return outboxRepository.findAll().stream()
                .filter(o ->
                        CustomerCreditIssuanceGLPostingEvent.class.getName().equals(o.getEventType()))
                .findFirst()
                .map(o -> {
                    try {
                        return objectMapper.readValue(o.getPayload(), CustomerCreditIssuanceGLPostingEvent.class);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .orElseThrow();
    }

    /** Sum debit and credit amounts per GL account across all journal entries (lazy assoc loaded in-tx). */
    private void sumLines(Map<UUID, BigDecimal> debits, Map<UUID, BigDecimal> credits) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            for (JournalEntry entry : journalEntryRepository.findAll()) {
                for (JournalEntryLine line : entry.getLines()) {
                    BigDecimal debit = line.getDebitAmount() == null ? BigDecimal.ZERO : line.getDebitAmount();
                    BigDecimal credit = line.getCreditAmount() == null ? BigDecimal.ZERO : line.getCreditAmount();
                    debits.merge(line.getGlAccountId(), debit, BigDecimal::add);
                    credits.merge(line.getGlAccountId(), credit, BigDecimal::add);
                }
            }
        });
    }

    private UUID accountId(String accountCode) {
        return glAccountRepository
                .findByAccountCode(accountCode)
                .orElseThrow(() -> new IllegalStateException("Seed missing GL account " + accountCode))
                .getGlAccountId();
    }

    private UUID seedFinalizedInvoice(BigDecimal total) {
        UUID invoiceId = UUID.randomUUID();
        ExtInvoice invoice = ExtInvoice.builder()
                .invoiceId(invoiceId)
                .workorderId(UUID.randomUUID())
                .partyId(UUID.randomUUID().toString())
                .status("FINALIZED")
                .total(total)
                .invoiceCreatedAt(Instant.now())
                .finalizedAt(Instant.now())
                .aggregateVersion(1L)
                .updatedAt(Instant.now())
                .build();
        return extInvoiceRepository.save(invoice).getInvoiceId();
    }

    private UUID seedAvailablePayment(BigDecimal total) {
        UUID paymentId = UUID.randomUUID();
        ReceivablePayment payment = new ReceivablePayment();
        payment.setPaymentId(paymentId);
        payment.setCustomerId(UUID.randomUUID());
        payment.setCurrency("USD");
        payment.setTotalAmount(total);
        payment.setUnappliedAmount(total);
        payment.setStatus(ReceivablePaymentStatus.AVAILABLE);
        payment.setClearedAt(Instant.now());
        payment.setSourceEventId(UUID.randomUUID());
        payment.setCreatedBy("c1-975-it");
        return receivablePaymentRepository.save(payment).getPaymentId();
    }

    private PaymentApplicationRequest request(String requestId, UUID invoiceId, BigDecimal amount) {
        PaymentApplicationRequest.InvoiceApplication app = new PaymentApplicationRequest.InvoiceApplication();
        app.setInvoiceId(invoiceId);
        app.setAmountToApply(amount);
        PaymentApplicationRequest request = new PaymentApplicationRequest();
        request.setApplicationRequestId(requestId);
        request.setApplications(List.of(app));
        return request;
    }
}
