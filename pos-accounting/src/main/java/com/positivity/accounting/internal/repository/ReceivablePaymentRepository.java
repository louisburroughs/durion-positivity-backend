package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
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
     * Payments of the given customers in {@code status}, oldest cleared first (#2508): with {@code
     * AVAILABLE}, the walk-in payments with money left unapplied.
     *
     * @param customerIds customers to include
     * @param status      payment status
     * @return matching payments, ordered by clearedAt ascending
     */
    @NonNull
    List<ReceivablePayment> findByCustomerIdInAndStatusOrderByClearedAtAscPaymentIdAsc(
            @NonNull Collection<UUID> customerIds, @NonNull ReceivablePaymentStatus status);

    /**
     * A page of payments in {@code status}, for the unapplied-payments list (#2502); the caller
     * supplies the order.
     */
    @NonNull
    Page<ReceivablePayment> findByStatus(@NonNull ReceivablePaymentStatus status, @NonNull Pageable pageable);

    /** A page of one customer's payments in {@code status} (#2502); the caller supplies the order. */
    @NonNull
    Page<ReceivablePayment> findByStatusAndCustomerId(
            @NonNull ReceivablePaymentStatus status, @NonNull UUID customerId, @NonNull Pageable pageable);

    /** Count and unapplied total of every payment in {@code status} (#2502). */
    @Query("SELECT new com.positivity.accounting.internal.repository.ReceivablePaymentTotals(COUNT(rp),"
            + " COALESCE(SUM(rp.unappliedAmount), 0)) FROM ReceivablePayment rp WHERE rp.status = :status")
    @NonNull
    ReceivablePaymentTotals totalsByStatus(@Param("status") @NonNull ReceivablePaymentStatus status);

    /** Count and unapplied total of one customer's payments in {@code status} (#2502). */
    @Query("SELECT new com.positivity.accounting.internal.repository.ReceivablePaymentTotals(COUNT(rp),"
            + " COALESCE(SUM(rp.unappliedAmount), 0)) FROM ReceivablePayment rp"
            + " WHERE rp.status = :status AND rp.customerId = :customerId")
    @NonNull
    ReceivablePaymentTotals totalsByStatusAndCustomerId(
            @Param("status") @NonNull ReceivablePaymentStatus status, @Param("customerId") @NonNull UUID customerId);

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

    /**
     * Take Postgres's transaction-scoped advisory lock {@code key} on this transaction's own connection,
     * waiting while another transaction holds it; it is released when this transaction ends (#2556).
     * Postgres only: call it through {@code PaymentIntentLock}, which skips it on any other database.
     *
     * @param key the lock key
     * @return always 1
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) AS held", nativeQuery = true)
    int takeAdvisoryTransactionLock(@Param("key") long key);
}
