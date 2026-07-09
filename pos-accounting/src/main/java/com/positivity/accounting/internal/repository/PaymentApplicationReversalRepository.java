package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
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
}
