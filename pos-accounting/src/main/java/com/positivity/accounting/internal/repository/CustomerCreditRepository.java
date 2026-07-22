package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.CustomerCredit;
import com.positivity.accounting.internal.enums.CustomerCreditStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for CustomerCredit entity.
 * Supports customer credit balance queries and history.
 */
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

    /**
     * Credits in a consumption state (issue #992).
     *
     * @param status   consumption state
     * @param pageable pagination parameters
     * @return page of credits
     */
    Page<CustomerCredit> findByStatus(CustomerCreditStatus status, Pageable pageable);

    /**
     * Credits for one customer in a consumption state (issue #992).
     *
     * @param customerId customer identifier
     * @param status     consumption state
     * @param pageable   pagination parameters
     * @return page of credits
     */
    Page<CustomerCredit> findByCustomerIdAndStatus(UUID customerId, CustomerCreditStatus status, Pageable pageable);

    /**
     * Total credit still owed to a customer across all their credits — the customer's slice of
     * the Customer Credit Liability (2300) control account (issue #992, closing #975 AC-7).
     *
     * @param customerId customer identifier
     * @return sum of {@code amount − applied − refunded} (zero when the customer has no credits)
     */
    @Query("SELECT COALESCE(SUM(cc.amount - cc.appliedAmount - cc.refundedAmount), 0) FROM CustomerCredit cc"
            + " WHERE cc.customerId = :customerId")
    java.math.BigDecimal sumOpenAmountByCustomerId(UUID customerId);

    /**
     * Total credit still owed across every customer. This is the subledger side of the #975
     * AC-7 invariant: it must equal the 2300 control-account balance.
     *
     * @return sum of {@code amount − applied − refunded} over all credits
     */
    @Query("SELECT COALESCE(SUM(cc.amount - cc.appliedAmount - cc.refundedAmount), 0) FROM CustomerCredit cc")
    java.math.BigDecimal sumOpenAmount();
}
