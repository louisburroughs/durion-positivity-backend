package com.positivity.accounting.repository;

import com.positivity.accounting.domain.PaymentAppliedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Repository for PaymentAppliedEvent entities.
 */
@Repository
public interface PaymentAppliedEventRepository extends JpaRepository<PaymentAppliedEvent, Long> {
    
    /**
     * Find all payment events for a specific invoice.
     */
    List<PaymentAppliedEvent> findByInvoiceIdOrderByTimestampDesc(String invoiceId);
    
    /**
     * Find a payment event by idempotency key.
     */
    Optional<PaymentAppliedEvent> findByIdempotencyKey(String idempotencyKey);
    
    /**
     * Calculate total paid amount for an invoice (excluding failed payments).
     */
    @Query("SELECT COALESCE(SUM(p.paymentAmount), 0) FROM PaymentAppliedEvent p " +
           "WHERE p.invoiceId = :invoiceId AND p.status != 'FAILED'")
    BigDecimal calculateTotalPaid(@Param("invoiceId") String invoiceId);
}
