package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for ReceivablePayment entity.
 * Supports payment availability queries and customer payment history.
 */
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

    /**
     * Sum of {@code totalAmount} for payments whose {@code clearedAt} falls in the inclusive
     * instant range. Feeds the {@code received} figure of collections analytics (issue #1622):
     * cash actually taken in, whether or not it has been applied to an invoice yet.
     *
     * <p>Deliberately keyed on {@code clearedAt} (set from the settlement event, when cash was
     * actually taken in) rather than {@code createdAt} (row bookkeeping time, i.e. when this
     * replica row was written) — the two can differ, and {@code received} must reflect the
     * former.
     *
     * @param start inclusive lower bound
     * @param end   inclusive upper bound
     * @return total cleared amount within the range; zero when there are none
     */
    @Query("SELECT COALESCE(SUM(rp.totalAmount), 0) FROM ReceivablePayment rp"
            + " WHERE rp.clearedAt BETWEEN :start AND :end")
    BigDecimal sumTotalAmountByClearedAtBetween(@Param("start") Instant start, @Param("end") Instant end);
}
