package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.service.PaymentApplicationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Concurrency integration tests for Story C4 (issue #936): optimistic locking
 * on {@link ReceivablePayment} plus retry-once-on-conflict in the payment
 * application flow.
 *
 * <p>
 * Runs against H2 with real transactions. The {@link PaymentApplicationService}
 * bean under test is the {@link RetryingPaymentApplicationService} decorator
 * (it is {@code @Primary}), so these tests exercise the production call chain:
 * decorator (non-transactional) → transactional
 * {@link PaymentApplicationServiceImpl}.
 */
@DisplayName("ReceivablePayment Optimistic Locking Concurrency IT")
class ReceivablePaymentOptimisticLockingIT extends BaseIntegrationTest {

    private static final AtomicInteger UUID_COUNTER = new AtomicInteger(0xC400);

    private static UUID nextUuid() {
        return UUID.fromString(String.format("00000000-0000-0000-c4c4-%012x", UUID_COUNTER.getAndIncrement()));
    }

    @Autowired
    private PaymentApplicationService paymentApplicationService;

    @Autowired
    private ReceivablePaymentRepository receivablePaymentRepository;

    @Autowired
    private PaymentApplicationRepository paymentApplicationRepository;

    @Autowired
    private PaymentApplicationReversalRepository paymentApplicationReversalRepository;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID paymentId;
    private UUID customerId;
    private UUID invoice1Id;
    private UUID invoice2Id;

    @BeforeEach
    void setUpData() {
        paymentApplicationReversalRepository.deleteAll();
        paymentApplicationRepository.deleteAll();
        receivablePaymentRepository.deleteAll();
        extInvoiceRepository.deleteAll();

        paymentId = nextUuid();
        customerId = nextUuid();
        invoice1Id = nextUuid();
        invoice2Id = nextUuid();

        seedReplicaInvoice(invoice1Id);
        seedReplicaInvoice(invoice2Id);
    }

    private void seedReplicaInvoice(UUID invoiceId) {
        extInvoiceRepository.save(ExtInvoice.builder()
                .invoiceId(invoiceId)
                .workorderId(nextUuid())
                .partyId(customerId.toString())
                .status("FINALIZED")
                .total(new BigDecimal("10000.00"))
                .finalizedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build());
    }

    private void seedPayment(String amount) {
        ReceivablePayment payment = new ReceivablePayment();
        payment.setPaymentId(paymentId);
        payment.setCustomerId(customerId);
        payment.setTotalAmount(new BigDecimal(amount));
        payment.setUnappliedAmount(new BigDecimal(amount));
        payment.setCurrency("USD");
        payment.setStatus(ReceivablePaymentStatus.AVAILABLE);
        payment.setClearedAt(Instant.parse("2026-01-01T00:00:00Z"));
        payment.setSourceEventId(nextUuid());
        receivablePaymentRepository.save(payment);
    }

    private PaymentApplicationRequest applicationRequest(UUID invoiceId, String amount) {
        PaymentApplicationRequest.InvoiceApplication app = new PaymentApplicationRequest.InvoiceApplication();
        app.setInvoiceId(invoiceId);
        app.setAmountToApply(new BigDecimal(amount));
        PaymentApplicationRequest request = new PaymentApplicationRequest();
        request.setApplicationRequestId(nextUuid().toString());
        request.setApplications(List.of(app));
        return request;
    }

    @Test
    @DisplayName(
            "C4-IT1: Stale write to receivable_payment fails at commit with optimistic-lock conflict (deterministic)")
    void staleWrite_failsWithOptimisticLockConflict() {
        seedPayment("1000.00");

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> outer.executeWithoutResult(outerStatus -> {
                    // Outer transaction reads version N.
                    ReceivablePayment stale =
                            receivablePaymentRepository.findById(paymentId).orElseThrow();

                    // A second transaction commits an update, bumping the row to N+1.
                    inner.executeWithoutResult(innerStatus -> {
                        ReceivablePayment fresh =
                                receivablePaymentRepository.findById(paymentId).orElseThrow();
                        fresh.applyAmount(new BigDecimal("100.00"));
                        receivablePaymentRepository.save(fresh);
                    });

                    // Outer now writes its stale copy (version N) — must conflict at commit.
                    stale.applyAmount(new BigDecimal("200.00"));
                    receivablePaymentRepository.save(stale);
                }))
                .matches(RetryingPaymentApplicationService::isOptimisticLockConflict);

        // Only the inner transaction's update survived; the balance never double-decrements.
        ReceivablePayment result =
                receivablePaymentRepository.findById(paymentId).orElseThrow();
        assertThat(result.getUnappliedAmount()).isEqualByComparingTo("900.00");
        assertThat(result.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "C4-IT2: Two concurrent applications with sufficient funds both succeed; final balance exact, never negative")
    void concurrentApplications_sufficientFunds_bothSucceed() throws Exception {
        seedPayment("1000.00");
        PaymentApplicationRequest request1 = applicationRequest(invoice1Id, "300.00");
        PaymentApplicationRequest request2 = applicationRequest(invoice2Id, "400.00");

        List<Outcome> outcomes = runConcurrently(request1, request2);

        assertThat(outcomes)
                .allSatisfy(outcome -> assertThat(outcome.failure())
                        .as("both applications must succeed (loser retries once and wins)")
                        .isNull());

        ReceivablePayment payment =
                receivablePaymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("300.00");
        assertThat(payment.getUnappliedAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(payment.getStatus()).isEqualTo(ReceivablePaymentStatus.AVAILABLE);
        assertThat(paymentApplicationRepository.findByPayment_PaymentId(paymentId))
                .hasSize(2);
    }

    @Test
    @DisplayName(
            "C4-IT3: Two concurrent applications with funds for only one — one succeeds, other rejected, balance never negative")
    void concurrentApplications_insufficientFundsForBoth_oneRejected() throws Exception {
        seedPayment("500.00");
        PaymentApplicationRequest request1 = applicationRequest(invoice1Id, "400.00");
        PaymentApplicationRequest request2 = applicationRequest(invoice2Id, "400.00");

        List<Outcome> outcomes = runConcurrently(request1, request2);

        List<Outcome> successes =
                outcomes.stream().filter(o -> o.failure() == null).toList();
        List<Outcome> failures =
                outcomes.stream().filter(o -> o.failure() != null).toList();

        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);

        // The loser re-reads fresh state on retry and gets the business rejection
        // (400 insufficient funds), or a 409 if it conflicted twice.
        assertThat(failures.get(0).failure())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.CONFLICT));

        ReceivablePayment payment =
                receivablePaymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("100.00");
        assertThat(payment.getUnappliedAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(paymentApplicationRepository.findByPayment_PaymentId(paymentId))
                .hasSize(1);
    }

    private record Outcome(PaymentApplicationResponse response, Exception failure) {}

    /**
     * Runs two apply-payment requests against the same payment on two threads,
     * released simultaneously via a {@link CountDownLatch} so both overlap.
     * Outcome assertions are interleaving-independent: whichever thread loses
     * the race goes through the retry path, so final-state assertions are
     * deterministic even though the winner is not.
     */
    private List<Outcome> runConcurrently(PaymentApplicationRequest request1, PaymentApplicationRequest request2)
            throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Outcome> task1 = () -> applyOnce(startLatch, request1);
            Callable<Outcome> task2 = () -> applyOnce(startLatch, request2);
            Future<Outcome> future1 = executor.submit(task1);
            Future<Outcome> future2 = executor.submit(task2);
            startLatch.countDown();
            return List.of(future1.get(60, TimeUnit.SECONDS), future2.get(60, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Outcome applyOnce(CountDownLatch startLatch, PaymentApplicationRequest request) throws Exception {
        startLatch.await(30, TimeUnit.SECONDS);
        try {
            return new Outcome(paymentApplicationService.applyPaymentToInvoices(paymentId, request), null);
        } catch (RuntimeException ex) {
            return new Outcome(null, ex);
        }
    }
}
