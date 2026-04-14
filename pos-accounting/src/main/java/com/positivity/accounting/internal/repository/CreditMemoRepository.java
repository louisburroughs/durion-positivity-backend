package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for CreditMemo entity.
 * Supports credit memo queries and lifecycle operations.
 */
@Repository
public interface CreditMemoRepository extends JpaRepository<CreditMemo, UUID> {

    /**
     * Find all credit memos for an invoice.
     *
     * @param originalInvoiceId invoice identifier
     * @return list of credit memos
     */
    List<CreditMemo> findByOriginalInvoiceId(UUID originalInvoiceId);

    /**
     * Find all credit memos for an invoice with pagination.
     *
     * @param originalInvoiceId invoice identifier
     * @param pageable pagination parameters
     * @return page of credit memos
     */
    Page<CreditMemo> findByOriginalInvoiceId(UUID originalInvoiceId, Pageable pageable);

    /**
     * Find all credit memos for a customer with pagination.
     *
     * @param customerId customer identifier
     * @param pageable   pagination parameters
     * @return page of credit memos
     */
    Page<CreditMemo> findByCustomerId(UUID customerId, Pageable pageable);

    /**
     * Find all credit memos by status.
     *
     * @param status   credit memo status
     * @param pageable pagination parameters
     * @return page of credit memos
     */
    Page<CreditMemo> findByStatus(CreditMemoStatus status, Pageable pageable);

    /**
     * Check if any credit memos exist for an invoice.
     *
     * @param originalInvoiceId invoice identifier
     * @return true if credit memos exist
     */
    boolean existsByOriginalInvoiceId(UUID originalInvoiceId);
}
