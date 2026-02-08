package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for PaymentApplicationReversal entity.
 * Supports reversal history queries and validation.
 */
@Repository
public interface PaymentApplicationReversalRepository extends JpaRepository<PaymentApplicationReversal, UUID> {

    /**
     * Find reversal by original payment application ID.
     * 
     * @param originalPaymentApplicationId original application identifier
     * @return reversal if exists
     */
    Optional<PaymentApplicationReversal> findByOriginalPaymentApplicationId(UUID originalPaymentApplicationId);

    /**
     * Find all reversals for a payment application.
     * 
     * @param originalPaymentApplicationId original application identifier
     * @return list of reversals (should be 0 or 1 in normal cases)
     */
    List<PaymentApplicationReversal> findAllByOriginalPaymentApplicationId(UUID originalPaymentApplicationId);

    /**
     * Find all reversals with pagination.
     * 
     * @param pageable pagination parameters
     * @return page of reversals
     */
    Page<PaymentApplicationReversal> findAll(Pageable pageable);

    /**
     * Check if application was already reversed.
     * 
     * @param originalPaymentApplicationId original application identifier
     * @return true if already reversed
     */
    boolean existsByOriginalPaymentApplicationId(UUID originalPaymentApplicationId);
}
