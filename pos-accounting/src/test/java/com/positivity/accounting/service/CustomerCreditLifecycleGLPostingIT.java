package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.CustomerCreditApplicationRequest;
import com.positivity.accounting.internal.dto.CustomerCreditIssuanceGLPostingEvent;
import com.positivity.accounting.internal.dto.CustomerCreditRefundRequest;
import com.positivity.accounting.internal.dto.CustomerCreditReliefGLPostingEvent;
import com.positivity.accounting.internal.dto.CustomerCreditTransactionResponse;
import com.positivity.accounting.internal.dto.PaymentApplicationGLPostingEvent;
import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.enums.CustomerCreditStatus;
import com.positivity.accounting.internal.handler.CustomerCreditIssuanceGLPostingEventHandler;
import com.positivity.accounting.internal.handler.CustomerCreditReliefGLPostingEventHandler;
import com.positivity.accounting.internal.handler.PaymentApplicationGLPostingEventHandler;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.CustomerCreditTransactionRepository;
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
 * Real-Postgres IT for the full customer-credit lifecycle (parity-C1 follow-on, issue #992):
 * <b>issuance → application/refund</b>, and the GL symmetry that makes the Customer Credit
 * Liability (2300) control account net back to zero.
 *
 * <p>Runs the full Flyway chain + repeatable seed — which now carries the
 * {@code CUSTOMER_CREDIT_APPLICATION} / {@code CUSTOMER_CREDIT_REFUND} posting categories and
 * their mapping keys — on a Testcontainers Postgres, so the seed and the
 * {@link GLMappingResolver} account resolution are exercised end-to-end. H2 does not enforce the
 * Postgres CHECK/FK constraints the seed and the new lifecycle columns rely on.
 *
 * <p>Covers the issue's acceptance criteria:
 *
 * <ul>
 *   <li><b>AC-1</b> — applying a credit posts Dr 2300 / Cr AR, in the same transaction, idempotent;</li>
 *   <li><b>AC-2</b> — refunding posts Dr 2300 / Cr Undeposited Funds with the same guarantees;</li>
 *   <li><b>AC-3</b> — across issuance → application → refund, 2300 nets to zero;</li>
 *   <li><b>AC-4</b> — Σ open customer credits == the 2300 control-account balance (closes #975 AC-7);</li>
 *   <li><b>AC-5</b> — partial draw-down leaves a residual credit and a matching residual liability.</li>
 * </ul>
 *
 * <p>Skipped cleanly when Docker is unavailable ({@code disabledWithoutDocker}).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Customer credit lifecycle GL relief (parity-C1 #992, real Postgres)")
class CustomerCreditLifecycleGLPostingIT {

    private static final String USER = "c1-992-it";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private PaymentApplicationServiceImpl paymentApplicationService;

    @Autowired
    private CustomerCreditService customerCreditService;

    @Autowired
    private PaymentApplicationGLPostingEventHandler cashReceiptHandler;

    @Autowired
    private CustomerCreditIssuanceGLPostingEventHandler issuanceHandler;

    @Autowired
    private CustomerCreditReliefGLPostingEventHandler reliefHandler;

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
    private CustomerCreditTransactionRepository creditTransactionRepository;

    @Autowired
    private CreditMemoRepository creditMemoRepository;

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
        creditMemoRepository.deleteAll();
        creditTransactionRepository.deleteAll();
        customerCreditRepository.deleteAll();
        paymentApplicationRepository.deleteAll();
        receivablePaymentRepository.deleteAll();
        extInvoiceRepository.deleteAll();
    }

    @Test
    @DisplayName("AC-1/AC-3/AC-4: apply the whole credit to a later invoice -> 2300 nets to zero")
    void fullApplication_netsLiabilityToZero() {
        // Overpay a 400 invoice with 1000 -> 600 customer credit (Cr 2300 = 600).
        UUID creditId = overpayAndIssueCredit(new BigDecimal("1000.00"), new BigDecimal("400.00"));
        UUID customerId =
                customerCreditRepository.findById(creditId).orElseThrow().getCustomerId();

        // A later 600 invoice for the same customer, settled entirely by the credit.
        UUID laterInvoiceId = seedFinalizedInvoice(new BigDecimal("600.00"), customerId);
        CustomerCreditTransactionResponse applied = customerCreditService.applyCreditToInvoice(
                creditId, applicationRequest("apply-1", laterInvoiceId, new BigDecimal("600.00")), USER);

        assertThat(applied.isReplayed()).isFalse();
        assertThat(applied.getCreditOpenAmountAfter()).isEqualByComparingTo("0.00");
        assertThat(applied.getInvoiceBalanceAfter()).isEqualByComparingTo("0.00");

        // AC-1: the relief work item was enqueued in the application's transaction.
        assertThat(outboxRepository.findAll())
                .extracting(EventOutbox::getEventType)
                .contains(CustomerCreditReliefGLPostingEvent.class.getName());

        drainOutbox();

        UUID creditLiability = accountId("2300");
        UUID accountsReceivable = accountId("1200");
        Map<UUID, BigDecimal> debits = new HashMap<>();
        Map<UUID, BigDecimal> credits = new HashMap<>();
        sumLines(debits, credits);

        // AC-1: Dr 2300 = 600, and the AR credit total is 400 (cash receipt) + 600 (credit applied).
        assertThat(debits.getOrDefault(creditLiability, BigDecimal.ZERO)).isEqualByComparingTo("600.00");
        assertThat(credits.getOrDefault(accountsReceivable, BigDecimal.ZERO)).isEqualByComparingTo("1000.00");

        // AC-3: the 2300 control account nets to zero for a fully-consumed credit.
        assertThat(netLiability()).isEqualByComparingTo("0.00");

        // AC-4: subledger agrees — the credit is CONSUMED with nothing open.
        assertThat(customerCreditRepository.sumOpenAmount()).isEqualByComparingTo("0.00");
        assertThat(customerCreditRepository.findById(creditId).orElseThrow().getStatus())
                .isEqualTo(CustomerCreditStatus.CONSUMED);
        assertThat(customerCreditRepository.sumOpenAmount()).isEqualByComparingTo(netLiability());

        // The draw-down row is linked back to the entry that discharged it.
        assertThat(creditTransactionRepository.findAll()).singleElement().satisfies(t -> {
            assertThat(t.getGlJournalEntryId()).isNotNull();
            assertThat(t.getGlPostedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("AC-2/AC-3: refunding the whole credit posts Dr 2300 / Cr Undeposited Funds and nets to zero")
    void fullRefund_netsLiabilityToZero() {
        UUID creditId = overpayAndIssueCredit(new BigDecimal("1000.00"), new BigDecimal("400.00"));

        CustomerCreditTransactionResponse refunded =
                customerCreditService.refundCredit(creditId, refundRequest("refund-1", new BigDecimal("600.00")), USER);

        assertThat(refunded.getInvoiceId()).isNull();
        assertThat(refunded.getCreditOpenAmountAfter()).isEqualByComparingTo("0.00");

        drainOutbox();

        UUID creditLiability = accountId("2300");
        UUID undepositedFunds = accountId("1090");
        Map<UUID, BigDecimal> debits = new HashMap<>();
        Map<UUID, BigDecimal> credits = new HashMap<>();
        sumLines(debits, credits);

        // AC-2: Dr 2300 = 600; Undeposited Funds nets to the applied portion only (1000 in - 600 out).
        assertThat(debits.getOrDefault(creditLiability, BigDecimal.ZERO)).isEqualByComparingTo("600.00");
        assertThat(credits.getOrDefault(undepositedFunds, BigDecimal.ZERO)).isEqualByComparingTo("600.00");
        assertThat(debits.getOrDefault(undepositedFunds, BigDecimal.ZERO)).isEqualByComparingTo("1000.00");

        // AC-3: 2300 back to zero.
        assertThat(netLiability()).isEqualByComparingTo("0.00");
        assertThat(customerCreditRepository.sumOpenAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("AC-5: partial application then partial refund leaves matching residual credit and liability")
    void partialDrawDowns_leaveMatchingResidual() {
        UUID creditId = overpayAndIssueCredit(new BigDecimal("1000.00"), new BigDecimal("400.00"));
        UUID customerId =
                customerCreditRepository.findById(creditId).orElseThrow().getCustomerId();
        UUID laterInvoiceId = seedFinalizedInvoice(new BigDecimal("600.00"), customerId);

        customerCreditService.applyCreditToInvoice(
                creditId, applicationRequest("apply-part", laterInvoiceId, new BigDecimal("250.00")), USER);
        customerCreditService.refundCredit(creditId, refundRequest("refund-part", new BigDecimal("100.00")), USER);

        drainOutbox();

        // 600 issued - 250 applied - 100 refunded = 250 still open.
        assertThat(customerCreditRepository.findById(creditId).orElseThrow()).satisfies(credit -> {
            assertThat(credit.getOpenAmount()).isEqualByComparingTo("250.00");
            assertThat(credit.getStatus()).isEqualTo(CustomerCreditStatus.PARTIALLY_CONSUMED);
        });

        // AC-5 + AC-4: the residual liability equals the residual subledger amount, to the cent.
        assertThat(netLiability()).isEqualByComparingTo("250.00");
        assertThat(customerCreditRepository.sumOpenAmount()).isEqualByComparingTo(netLiability());
    }

    @Test
    @DisplayName("Replaying a requestId neither records a second draw-down nor re-relieves the liability")
    void replayedRequest_isANoOp() {
        UUID creditId = overpayAndIssueCredit(new BigDecimal("1000.00"), new BigDecimal("400.00"));

        customerCreditService.refundCredit(creditId, refundRequest("refund-dup", new BigDecimal("200.00")), USER);
        CustomerCreditTransactionResponse replay = customerCreditService.refundCredit(
                creditId, refundRequest("refund-dup", new BigDecimal("200.00")), USER);

        assertThat(replay.isReplayed()).isTrue();
        assertThat(creditTransactionRepository.findAll()).hasSize(1);

        drainOutbox();
        assertThat(netLiability()).isEqualByComparingTo("400.00");

        // Re-dispatching the already-posted relief work item posts nothing further.
        long entriesBefore = journalEntryRepository.count();
        reliefHandler.onCustomerCreditReliefGLPosting(reliefEventFromEnqueued());
        assertThat(journalEntryRepository.count()).isEqualTo(entriesBefore);
        assertThat(netLiability()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("#997: the T8 credit query returns every memo that posted in-period except DRAFT")
    void t8CreditSelection_includesEveryPostedStatus() {
        // The unit test for #997 stubs this repository method, so it would pass even if the derived
        // query resolved wrongly. Exercise the real query against Postgres: a credit's Dr 2200 is
        // permanent, so POSTED / APPLIED / VOIDED must all count toward the period they posted in,
        // and only DRAFT (which never posted) is excluded.
        Instant inPeriod = Instant.parse("2026-06-15T00:00:00Z");
        Instant outOfPeriod = Instant.parse("2026-05-15T00:00:00Z");

        UUID posted = seedCreditMemo(CreditMemoStatus.POSTED, inPeriod);
        UUID applied = seedCreditMemo(CreditMemoStatus.APPLIED, inPeriod);
        UUID voided = seedCreditMemo(CreditMemoStatus.VOIDED, inPeriod);
        seedCreditMemo(CreditMemoStatus.DRAFT, inPeriod);
        seedCreditMemo(CreditMemoStatus.POSTED, outOfPeriod);

        List<CreditMemo> selected = creditMemoRepository.findByStatusNotAndPostedTimestampBetween(
                CreditMemoStatus.DRAFT, Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-30T23:59:59Z"));

        assertThat(selected).extracting(CreditMemo::getCreditMemoId).containsExactlyInAnyOrder(posted, applied, voided);
    }

    private UUID seedCreditMemo(CreditMemoStatus status, Instant postedAt) {
        CreditMemo memo = new CreditMemo();
        memo.setOriginalInvoiceId(UUID.randomUUID());
        memo.setCustomerId(UUID.randomUUID());
        memo.setCreditAmount(new BigDecimal("100.00"));
        memo.setTaxAmountReversed(new BigDecimal("8.00"));
        memo.setReasonCode("RETURN");
        memo.setStatus(status);
        memo.setPostedTimestamp(postedAt);
        memo.setCreatedByUserId(USER);
        memo.setCurrency("USD");
        return creditMemoRepository.save(memo).getCreditMemoId();
    }

    // ===== helpers =====

    /**
     * Overpay an invoice, drain the issuance legs, and return the resulting credit id. Leaves the
     * ledger with {@code Cr 2300 = paymentTotal − invoiceDue}.
     */
    private UUID overpayAndIssueCredit(BigDecimal paymentTotal, BigDecimal invoiceDue) {
        UUID invoiceId = seedFinalizedInvoice(invoiceDue, null);
        UUID paymentId = seedAvailablePayment(paymentTotal, customerOf(invoiceId));
        paymentApplicationService.applyPaymentToInvoices(
                paymentId, paymentRequest(UUID.randomUUID().toString(), invoiceId, paymentTotal));
        drainOutbox();
        return customerCreditRepository.findAll().getFirst().getCreditId();
    }

    private UUID customerOf(UUID invoiceId) {
        return UUID.fromString(
                extInvoiceRepository.findById(invoiceId).orElseThrow().getPartyId());
    }

    /** Credit-normal balance of the 2300 control account: Σcredit − Σdebit. */
    private BigDecimal netLiability() {
        UUID creditLiability = accountId("2300");
        Map<UUID, BigDecimal> debits = new HashMap<>();
        Map<UUID, BigDecimal> credits = new HashMap<>();
        sumLines(debits, credits);
        return credits.getOrDefault(creditLiability, BigDecimal.ZERO)
                .subtract(debits.getOrDefault(creditLiability, BigDecimal.ZERO));
    }

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

    private CustomerCreditReliefGLPostingEvent reliefEventFromEnqueued() {
        return outboxRepository.findAll().stream()
                .filter(o -> CustomerCreditReliefGLPostingEvent.class.getName().equals(o.getEventType()))
                .findFirst()
                .map(o -> {
                    try {
                        return objectMapper.readValue(o.getPayload(), CustomerCreditReliefGLPostingEvent.class);
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

    private UUID seedFinalizedInvoice(BigDecimal total, UUID customerId) {
        ExtInvoice invoice = ExtInvoice.builder()
                .invoiceId(UUID.randomUUID())
                .workorderId(UUID.randomUUID())
                .partyId((customerId == null ? UUID.randomUUID() : customerId).toString())
                .status("FINALIZED")
                .total(total)
                .invoiceCreatedAt(Instant.now())
                .finalizedAt(Instant.now())
                .aggregateVersion(1L)
                .updatedAt(Instant.now())
                .build();
        return extInvoiceRepository.save(invoice).getInvoiceId();
    }

    private UUID seedAvailablePayment(BigDecimal total, UUID customerId) {
        ReceivablePayment payment = new ReceivablePayment();
        payment.setPaymentId(UUID.randomUUID());
        payment.setCustomerId(customerId);
        payment.setCurrency("USD");
        payment.setTotalAmount(total);
        payment.setUnappliedAmount(total);
        payment.setStatus(ReceivablePaymentStatus.AVAILABLE);
        payment.setClearedAt(Instant.now());
        payment.setSourceEventId(UUID.randomUUID());
        payment.setCreatedBy(USER);
        return receivablePaymentRepository.save(payment).getPaymentId();
    }

    private PaymentApplicationRequest paymentRequest(String requestId, UUID invoiceId, BigDecimal amount) {
        PaymentApplicationRequest.InvoiceApplication app = new PaymentApplicationRequest.InvoiceApplication();
        app.setInvoiceId(invoiceId);
        app.setAmountToApply(amount);
        PaymentApplicationRequest request = new PaymentApplicationRequest();
        request.setApplicationRequestId(requestId);
        request.setApplications(List.of(app));
        return request;
    }

    private CustomerCreditApplicationRequest applicationRequest(String requestId, UUID invoiceId, BigDecimal amount) {
        return CustomerCreditApplicationRequest.builder()
                .requestId(requestId)
                .invoiceId(invoiceId)
                .amount(amount)
                .build();
    }

    private CustomerCreditRefundRequest refundRequest(String requestId, BigDecimal amount) {
        return CustomerCreditRefundRequest.builder()
                .requestId(requestId)
                .amount(amount)
                .build();
    }
}
