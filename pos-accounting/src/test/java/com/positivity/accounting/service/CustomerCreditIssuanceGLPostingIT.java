package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.CustomerCreditIssuanceGLPostingEvent;
import com.positivity.accounting.internal.dto.PaymentApplicationGLPostingEvent;
import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.handler.CustomerCreditIssuanceGLPostingEventHandler;
import com.positivity.accounting.internal.handler.PaymentApplicationGLPostingEventHandler;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.EventOutboxRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.service.OutboxProcessor;
import com.positivity.accounting.internal.service.PaymentApplicationServiceImpl;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real-Postgres IT for customer-credit issuance GL posting (parity-C1, issue
 * #975). Runs the full Flyway chain + repeatable seed (which now carries the
 * {@code CUSTOMER_CREDIT_ISSUANCE} posting category and its two mapping keys)
 * on a Testcontainers Postgres, so the seed correctness and the
 * {@link com.positivity.accounting.service.GLMappingResolver} account
 * resolution are exercised end-to-end — H2 does not enforce the Postgres
 * CHECK/FK constraints the seed relies on.
 *
 * <p>Exercises the overpayment path: applying more than an invoice's balance
 * records a {@code CustomerCredit} for the excess and enqueues both GL legs
 * (AR cash receipt + credit issuance). Both work items are then drained from the
 * outbox and posted, and the ledger is asserted balanced across both entries.
 *
 * <p>Skipped cleanly when Docker is unavailable ({@code disabledWithoutDocker}).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Customer credit issuance GL posting (parity-C1 #975, real Postgres)")
class CustomerCreditIssuanceGLPostingIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Schema + seed come from the real Flyway chain, not Hibernate DDL.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private PaymentApplicationServiceImpl paymentApplicationService;

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
