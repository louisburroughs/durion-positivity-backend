package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PaymentApplication;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for PaymentApplication entity.
 * Supports application history queries and idempotency checks.
 */
public interface PaymentApplicationRepository extends JpaRepository<PaymentApplication, UUID> {

    /**
     * Find all applications for a payment.
     *
     * @param paymentId payment identifier
     * @return list of applications
     */
    List<PaymentApplication> findByPayment_PaymentId(UUID paymentId);

    /**
     * Find all applications for an invoice.
     *
     * @param invoiceId invoice identifier
     * @return list of applications
     */
    List<PaymentApplication> findByInvoiceId(UUID invoiceId);

    /**
     * Find all applications for a set of invoices in one round trip. Used by the payment-lag
     * cohorts analytics endpoint (Wave 2 E3, issue #1591) to bulk-load applications for every
     * invoice issued in the requested window, avoiding one query per invoice.
     *
     * @param invoiceIds invoice identifiers
     * @return applications for any of the given invoices (unordered; callers sort per invoice)
     */
    List<PaymentApplication> findByInvoiceIdIn(Collection<UUID> invoiceIds);

    /**
     * Find all applications for a customer with pagination.
     *
     * @param customerId customer identifier
     * @param pageable   pagination parameters
     * @return page of applications
     */
    Page<PaymentApplication> findByCustomerId(UUID customerId, Pageable pageable);

    /**
     * Find application by request ID (for idempotency).
     *
     * @param applicationRequestId idempotency key
     * @return application if already processed
     */
    Optional<PaymentApplication> findByApplicationRequestId(String applicationRequestId);

    /**
     * Find all applications by request ID (for multi-invoice idempotency).
     *
     * @param applicationRequestId idempotency key
     * @return list of all applications for this request
     */
    List<PaymentApplication> findAllByApplicationRequestId(String applicationRequestId);

    /**
     * Check if application request was already processed (idempotency check).
     *
     * @param applicationRequestId idempotency key
     * @return true if already processed
     */
    boolean existsByApplicationRequestId(String applicationRequestId);

    /**
     * Find applications whose application timestamp falls in the inclusive instant range. Used
     * by the invoiced-vs-collected analytics endpoint (Wave 2 E2, issue #1590) to sum settled cash
     * by application date, independent of which invoice or finalization period it settles.
     *
     * @param start inclusive lower bound
     * @param end   inclusive upper bound
     * @return applications posted within the range (unordered)
     */
    List<PaymentApplication> findByApplicationTimestampBetween(Instant start, Instant end);

    /**
     * Sum of all applied amounts for a payment.
     *
     * @param paymentId payment identifier
     * @return total applied amount
     */
    @Query(
            "SELECT COALESCE(SUM(pa.appliedAmount), 0) FROM PaymentApplication pa WHERE pa.payment.paymentId = :paymentId")
    java.math.BigDecimal sumAppliedAmountByPaymentId(UUID paymentId);

    /**
     * Sum of all applied amounts for an invoice.
     *
     * @param invoiceId invoice identifier
     * @return total applied amount
     */
    @Query("SELECT COALESCE(SUM(pa.appliedAmount), 0) FROM PaymentApplication pa WHERE pa.invoiceId = :invoiceId")
    java.math.BigDecimal sumAppliedAmountByInvoiceId(UUID invoiceId);
}
