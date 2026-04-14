package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Repository for ReceivablePayment entity.
 * Supports payment availability queries and customer payment history.
 */
@Repository
public interface ReceivablePaymentRepository extends JpaRepository<ReceivablePayment, UUID> {

    /**
     * Find payment by source event ID (for idempotency on PaymentCleared event).
     *
     * @param sourceEventId PaymentCleared event ID
     * @return payment if already processed
     */
    Optional<ReceivablePayment> findBySourceEventId(UUID sourceEventId);

    /**
     * Find all available payments for a customer (unappliedAmount > 0).
     *
     * @param customerId customer identifier
     * @return list of available payments
     */
    @Query(
            "SELECT rp FROM ReceivablePayment rp WHERE rp.customerId = :customerId AND rp.status = 'AVAILABLE' ORDER BY rp.clearedAt ASC")
    List<ReceivablePayment> findAvailablePaymentsByCustomer(UUID customerId);

    /**
     * Find all payments for a customer with pagination.
     *
     * @param customerId customer identifier
     * @param pageable   pagination parameters
     * @return page of payments
     */
    Page<ReceivablePayment> findByCustomerId(UUID customerId, Pageable pageable);

    /**
     * Find payments by status.
     *
     * @param status payment status
     * @return list of payments
     */
    List<ReceivablePayment> findByStatus(ReceivablePaymentStatus status);

    /**
     * Check if a payment exists by source event ID (idempotency check).
     *
     * @param sourceEventId PaymentCleared event ID
     * @return true if payment already processed
     */
    boolean existsBySourceEventId(UUID sourceEventId);
}
