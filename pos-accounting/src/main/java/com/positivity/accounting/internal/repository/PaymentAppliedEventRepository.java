package com.positivity.accounting.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.positivity.accounting.internal.entity.PaymentAppliedEvent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for PaymentAppliedEvent entities.
 */
@Repository
public interface PaymentAppliedEventRepository extends JpaRepository<PaymentAppliedEvent, UUID> {

    /**
     * Find all payment events for a specific invoice.
     */
    List<PaymentAppliedEvent> findByInvoiceIdOrderByTimestampDesc(UUID invoiceId);

    /**
     * Find a payment event by idempotency key.
     */
    Optional<PaymentAppliedEvent> findByIdempotencyKey(String idempotencyKey);

    /**
     * Calculate total paid amount for an invoice (excluding failed payments).
     */
    @Query("SELECT COALESCE(SUM(p.paymentAmount), 0) FROM PaymentAppliedEvent p " +
            "WHERE p.invoiceId = :invoiceId AND p.status != 'FAILED'")
    BigDecimal calculateTotalPaid(@Param("invoiceId") UUID invoiceId);
}
