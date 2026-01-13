package com.positivity.accounting.repository;

import com.positivity.accounting.domain.InvoiceStatusView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for InvoiceStatusView entities.
 */
@Repository
public interface InvoiceStatusViewRepository extends JpaRepository<InvoiceStatusView, Long> {
    
    /**
     * Find invoice status by invoice ID.
     */
    Optional<InvoiceStatusView> findByInvoiceId(String invoiceId);
}
