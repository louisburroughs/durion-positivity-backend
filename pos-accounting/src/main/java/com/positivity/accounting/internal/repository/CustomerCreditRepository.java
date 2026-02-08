package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.CustomerCredit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for CustomerCredit entity.
 * Supports customer credit balance queries and history.
 */
@Repository
public interface CustomerCreditRepository extends JpaRepository<CustomerCredit, UUID> {

    /**
     * Find all credits for a customer.
     * 
     * @param customerId customer identifier
     * @return list of credits
     */
    List<CustomerCredit> findByCustomerId(UUID customerId);

    /**
     * Find all credits for a customer with pagination.
     * 
     * @param customerId customer identifier
     * @param pageable   pagination parameters
     * @return page of credits
     */
    Page<CustomerCredit> findByCustomerId(UUID customerId, Pageable pageable);

    /**
     * Find credits by source payment.
     * 
     * @param sourcePaymentId payment identifier
     * @return list of credits
     */
    List<CustomerCredit> findBySourcePaymentId(UUID sourcePaymentId);

    /**
     * Sum of all credit amounts for a customer.
     * 
     * @param customerId customer identifier
     * @return total credit balance
     */
    @Query("SELECT COALESCE(SUM(cc.amount), 0) FROM CustomerCredit cc WHERE cc.customerId = :customerId")
    java.math.BigDecimal sumCreditAmountByCustomerId(UUID customerId);
}
