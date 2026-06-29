package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PaymentApplication;
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
