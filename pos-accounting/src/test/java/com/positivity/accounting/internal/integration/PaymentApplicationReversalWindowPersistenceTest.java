package com.positivity.accounting.internal.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes {@link PaymentApplicationReversalRepository#sumAmountByReversedAtBetween} against a real
 * (H2) schema to pin ADR-0057 decision 3 — <em>"a January payment reversed in March reduces March and
 * never restates January"</em> — and ADR-0047's correction-by-reversal rule.
 *
 * <p>That guarantee lives entirely in one JPQL string; every other test of the collections analytics
 * mocks this repository away and therefore asserts only that the service subtracts whatever the mock
 * returns. Two mutations of the query would satisfy all of those mocked tests while breaking the ADR,
 * and this test is written to fail under both:
 *
 * <ol>
 *   <li><strong>Filtering on the original application's date</strong> ({@code WHERE
 *       r.originalPaymentApplication.applicationTimestamp BETWEEN :start AND :end}) — that pulls the
 *       reversal back into January, restating a closed period. Killed by the January assertion below:
 *       the January total must be byte-for-byte what it was <em>before</em> the March reversal row
 *       existed.
 *   <li><strong>Summing the original application's {@code appliedAmount}</strong> instead of the
 *       reversal's own {@code amount}. Killed by the March assertion: the fixture's reversal amount
 *       (400.00) deliberately differs from the application's applied amount (1000.00), so the two
 *       columns are distinguishable.
 * </ol>
 *
 * <p><strong>On the divergent amounts.</strong> {@code PaymentApplicationServiceImpl#applyReversal}
 * currently copies the full applied amount onto the reversal, so no service path produces a partial
 * reversal today and the two columns coincide in service-written data. Nothing in the entity, the
 * schema, or the repository contract requires that, and the query's own javadoc states the contract
 * explicitly ("sums the reversal's own amount, not the original application's appliedAmount"). The
 * fixture is built at the persistence layer precisely so the column choice is observable: this is a
 * repository-contract test, and the contract must hold the day partial reversals become writable.
 *
 * <p>Named {@code *Test}, so it runs in surefire (the {@code test} phase) alongside the sibling
 * persistence tests in this package, not in failsafe.
 */
@Transactional
@DisplayName("PaymentApplicationReversal window query — movement basis (ADR-0057 D3)")
class PaymentApplicationReversalWindowPersistenceTest extends BaseIntegrationTest {

    /** The original application lands in January... */
    private static final Instant APPLIED_AT = Instant.parse("2031-01-15T10:00:00Z");

    /** ...and is reversed two months later, in March. */
    private static final Instant REVERSED_AT = Instant.parse("2031-03-20T10:00:00Z");

    private static final Instant JANUARY_START = Instant.parse("2031-01-01T00:00:00Z");
    private static final Instant JANUARY_END = Instant.parse("2031-01-31T23:59:59Z");
    private static final Instant MARCH_START = Instant.parse("2031-03-01T00:00:00Z");
    private static final Instant MARCH_END = Instant.parse("2031-03-31T23:59:59Z");

    private static final BigDecimal APPLIED_AMOUNT = new BigDecimal("1000.00");
    private static final BigDecimal REVERSAL_AMOUNT = new BigDecimal("400.00");

    @Autowired
    private PaymentApplicationReversalRepository reversalRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("A March reversal of a January application moves March and leaves January untouched")
    void reversalMovesTheWindowItWasRecordedInAndNeverRestatesTheEarlierOne() {
        PaymentApplication application = persistJanuaryApplication();

        // Baselines are read with the application persisted but BEFORE the reversal row exists, so
        // the assertions below are a genuine before/after of the same closed period rather than a
        // comparison against a hard-coded number that a sibling test's leftover rows could break.
        BigDecimal januaryBefore = reversalRepository.sumAmountByReversedAtBetween(JANUARY_START, JANUARY_END);
        BigDecimal marchBefore = reversalRepository.sumAmountByReversedAtBetween(MARCH_START, MARCH_END);

        persistMarchReversal(application);

        BigDecimal januaryAfter = reversalRepository.sumAmountByReversedAtBetween(JANUARY_START, JANUARY_END);
        BigDecimal marchAfter = reversalRepository.sumAmountByReversedAtBetween(MARCH_START, MARCH_END);

        assertThat(januaryAfter.subtract(januaryBefore))
                .as("January is closed: recording a March reversal must not change what January reports")
                .isEqualByComparingTo("0.00");

        assertThat(marchAfter.subtract(marchBefore))
                .as("March absorbs the reversal, at the reversal's own amount — not the original applied amount")
                .isEqualByComparingTo(REVERSAL_AMOUNT);
    }

    @Test
    @DisplayName("BETWEEN is inclusive at both endpoints: a reversal exactly on a boundary is counted once")
    void reversalOnAWindowBoundaryIsIncluded() {
        PaymentApplication application = persistJanuaryApplication();

        BigDecimal openBefore = reversalRepository.sumAmountByReversedAtBetween(REVERSED_AT, REVERSED_AT);
        BigDecimal lowerBefore = reversalRepository.sumAmountByReversedAtBetween(REVERSED_AT, MARCH_END);
        BigDecimal upperBefore = reversalRepository.sumAmountByReversedAtBetween(MARCH_START, REVERSED_AT);

        persistMarchReversal(application);

        assertThat(reversalRepository
                        .sumAmountByReversedAtBetween(REVERSED_AT, REVERSED_AT)
                        .subtract(openBefore))
                .as("degenerate window on the exact reversal instant")
                .isEqualByComparingTo(REVERSAL_AMOUNT);
        assertThat(reversalRepository
                        .sumAmountByReversedAtBetween(REVERSED_AT, MARCH_END)
                        .subtract(lowerBefore))
                .as("reversal sitting on the window's lower bound")
                .isEqualByComparingTo(REVERSAL_AMOUNT);
        assertThat(reversalRepository
                        .sumAmountByReversedAtBetween(MARCH_START, REVERSED_AT)
                        .subtract(upperBefore))
                .as("reversal sitting on the window's upper bound")
                .isEqualByComparingTo(REVERSAL_AMOUNT);
    }

    /**
     * Persists a payment and one application against it, dated {@link #APPLIED_AT}. Uses the
     * EntityManager directly so the rows are unambiguously INSERTed (PaymentApplication is immutable
     * and throws from {@code @PreUpdate}) and flushed before any query runs.
     */
    private PaymentApplication persistJanuaryApplication() {
        ReceivablePayment payment = new ReceivablePayment();
        payment.setCustomerId(UUID.randomUUID());
        payment.setCurrency("USD");
        payment.setTotalAmount(APPLIED_AMOUNT);
        payment.setUnappliedAmount(BigDecimal.ZERO);
        payment.setStatus(ReceivablePaymentStatus.FULLY_APPLIED);
        payment.setClearedAt(APPLIED_AT);
        payment.setSourceEventId(UUID.randomUUID());
        payment.setCreatedAt(APPLIED_AT);
        payment.setCreatedBy("testuser");
        entityManager.persist(payment);

        PaymentApplication application = new PaymentApplication();
        application.setPayment(payment);
        application.setInvoiceId(UUID.randomUUID());
        application.setCustomerId(payment.getCustomerId());
        application.setCurrency("USD");
        application.setAppliedAmount(APPLIED_AMOUNT);
        application.setApplicationTimestamp(APPLIED_AT);
        application.setApplicationRequestId(UUID.randomUUID().toString());
        application.setCreatedAt(APPLIED_AT);
        application.setCreatedBy("testuser");
        entityManager.persist(application);

        entityManager.flush();
        return application;
    }

    /**
     * Persists the single reversal of {@code application}, dated {@link #REVERSED_AT} — respecting
     * {@code uk_reversal_original_application}, which allows exactly one reversal per application.
     */
    private void persistMarchReversal(PaymentApplication application) {
        PaymentApplicationReversal reversal = new PaymentApplicationReversal();
        reversal.setOriginalPaymentApplication(application);
        reversal.setAmount(REVERSAL_AMOUNT);
        reversal.setReason("Customer disputed the January application");
        reversal.setReversedAt(REVERSED_AT);
        reversal.setReversedBy("testuser");
        entityManager.persist(reversal);
        entityManager.flush();
    }
}
