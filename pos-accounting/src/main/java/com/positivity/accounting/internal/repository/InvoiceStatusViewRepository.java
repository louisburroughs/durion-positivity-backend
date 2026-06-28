package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.InvoiceStatusView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for InvoiceStatusView entities.
 */
public interface InvoiceStatusViewRepository extends JpaRepository<InvoiceStatusView, UUID> {

    /**
     * Find invoice status by invoice ID.
     */
    Optional<InvoiceStatusView> findByInvoiceId(UUID invoiceId);
}
