package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for PaymentApplicationReversal entity.
 * Supports reversal history queries and validation.
 */
public interface PaymentApplicationReversalRepository extends JpaRepository<PaymentApplicationReversal, UUID> {

    /**
     * Find reversal by original payment application ID.
     *
     * @param originalPaymentApplicationId original application identifier
     * @return reversal if exists
     */
    Optional<PaymentApplicationReversal> findByOriginalPaymentApplication_PaymentApplicationId(
            UUID originalPaymentApplicationId);

    /**
     * Find all reversals for a payment application.
     *
     * @param originalPaymentApplicationId original application identifier
     * @return list of reversals (should be 0 or 1 in normal cases)
     */
    List<PaymentApplicationReversal> findAllByOriginalPaymentApplication_PaymentApplicationId(
            UUID originalPaymentApplicationId);

    /**
     * Find all reversals with pagination.
     *
     * @param pageable pagination parameters
     * @return page of reversals
     */
    @Override
    Page<PaymentApplicationReversal> findAll(Pageable pageable);

    /**
     * Check if application was already reversed.
     *
     * @param originalPaymentApplicationId original application identifier
     * @return true if already reversed
     */
    boolean existsByOriginalPaymentApplication_PaymentApplicationId(UUID originalPaymentApplicationId);

    /**
     * Sum of all reversed amounts for an invoice (via the reversed applications).
     *
     * @param invoiceId invoice identifier
     * @return total reversed amount
     */
    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(r.amount), 0) FROM PaymentApplicationReversal r"
            + " WHERE r.originalPaymentApplication.invoiceId = :invoiceId")
    java.math.BigDecimal sumReversedAmountByInvoiceId(UUID invoiceId);

    /**
     * Sum of reversal amounts whose {@code reversedAt} falls in the inclusive instant range. Used
     * by the invoiced-vs-collected analytics endpoint (Wave 2 E2, issue #1590) to net reversals out
     * of {@code collected} on a MOVEMENT basis: a reversal reduces the window it was recorded in,
     * never the window the original application landed in, so a closed period is never restated.
     *
     * <p>Sums the reversal's own {@code amount}, not the original application's {@code
     * appliedAmount} — a reversal carries the amount actually backed out.
     *
     * @param start inclusive lower bound
     * @param end   inclusive upper bound
     * @return total reversed amount recorded within the range; zero when there are none
     */
    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(r.amount), 0) FROM PaymentApplicationReversal r"
            + " WHERE r.reversedAt BETWEEN :start AND :end")
    java.math.BigDecimal sumAmountByReversedAtBetween(
            @org.springframework.data.repository.query.Param("start") java.time.Instant start,
            @org.springframework.data.repository.query.Param("end") java.time.Instant end);

    /**
     * Bulk-resolve which of the given payment application ids have a reversal. Used by the
     * payment-application list endpoint (Wave 2 E10, issue #1598) with {@code
     * includeReversed=true} to flag each row's {@code reversed} field in one round trip instead
     * of one existence check per row.
     *
     * @param originalPaymentApplicationIds candidate application ids
     * @return the subset of those ids that have a reversal
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT r.originalPaymentApplication.paymentApplicationId FROM PaymentApplicationReversal r"
                    + " WHERE r.originalPaymentApplication.paymentApplicationId IN :originalPaymentApplicationIds")
    List<UUID> findReversedApplicationIds(
            @org.springframework.data.repository.query.Param("originalPaymentApplicationIds")
                    Collection<UUID> originalPaymentApplicationIds);
}
