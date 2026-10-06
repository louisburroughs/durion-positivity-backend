package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

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
     * Reversed amounts summed per invoice of the reversed application, for many invoices in one
     * query (#2502); invoices with no reversal are absent.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT new com.positivity.accounting.internal.repository.InvoiceAmount(r.originalPaymentApplication.invoiceId, SUM(r.amount))"
                    + " FROM PaymentApplicationReversal r WHERE r.originalPaymentApplication.invoiceId IN :invoiceIds"
                    + " GROUP BY r.originalPaymentApplication.invoiceId")
    @NonNull
    List<InvoiceAmount> sumReversedAmountByInvoiceIdIn(@Param("invoiceIds") @NonNull Collection<UUID> invoiceIds);

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
     * Total reversed, within the inclusive instant range, of applications to invoices of the given
     * parties (#2508): the walk-in reversals the collections measure leaves out with their applications.
     *
     * @param start    inclusive lower bound
     * @param end      inclusive upper bound
     * @param partyIds parties as {@code ext_invoice.party_id} stores them (canonical UUID strings)
     * @return total reversed; zero when there is none
     */
    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(r.amount), 0) FROM PaymentApplicationReversal r"
            + " WHERE r.reversedAt BETWEEN :start AND :end"
            + " AND r.originalPaymentApplication.invoiceId IN"
            + " (SELECT i.invoiceId FROM ExtInvoice i WHERE i.partyId IN :partyIds)")
    java.math.@NonNull BigDecimal sumAmountByReversedAtBetweenAndInvoicePartyIdIn(
            @Param("start") java.time.@NonNull Instant start,
            @Param("end") java.time.@NonNull Instant end,
            @Param("partyIds") @NonNull Collection<String> partyIds);

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

    /** The reversals of any of the given applications, in one query (#2503). */
    @NonNull
    List<PaymentApplicationReversal> findByOriginalPaymentApplication_PaymentApplicationIdIn(
            @NonNull Collection<UUID> originalPaymentApplicationIds);

    /**
     * Whether any application of the payment that a person did not make (source other than {@code
     * MANUAL}) has been reversed (#2503, BR-8): an undone automatic application, by either automatic
     * path, means no automatic path applies the payment again.
     */
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(r) > 0 FROM PaymentApplicationReversal r"
            + " WHERE r.originalPaymentApplication.payment.paymentId = :paymentId"
            + " AND r.originalPaymentApplication.applicationSource"
            + " <> com.positivity.accounting.internal.enums.ApplicationSource.MANUAL")
    boolean existsReversedAutomaticApplication(@Param("paymentId") @NonNull UUID paymentId);
}
