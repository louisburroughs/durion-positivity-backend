package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for CreditMemo entity.
 * Supports credit memo queries and lifecycle operations.
 */
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

    /**
     * Sum of credit memo totals (credit + reversed tax) for an invoice in a given status.
     *
     * @param originalInvoiceId invoice identifier
     * @param status            credit memo status to include (normally POSTED)
     * @return total credited amount
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(SUM(cm.creditAmount + cm.taxAmountReversed), 0) FROM CreditMemo cm"
                    + " WHERE cm.originalInvoiceId = :originalInvoiceId AND cm.status = :status")
    java.math.BigDecimal sumCreditedAmountByInvoiceIdAndStatus(UUID originalInvoiceId, CreditMemoStatus status);

    /**
     * Sum of credit amounts (revenue portion only, excluding reversed tax) for an invoice in a
     * given status. Used to determine how much of the invoice's net (pre-tax) amount has already
     * been credited (issue #953).
     *
     * @param originalInvoiceId invoice identifier
     * @param status            credit memo status to include (normally POSTED)
     * @return total credited revenue amount
     */
    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(cm.creditAmount), 0) FROM CreditMemo cm"
            + " WHERE cm.originalInvoiceId = :originalInvoiceId AND cm.status = :status")
    java.math.BigDecimal sumCreditAmountByInvoiceIdAndStatus(UUID originalInvoiceId, CreditMemoStatus status);

    /**
     * Sum of reversed tax amounts for an invoice in a given status. Used for the final-credit
     * residual correction so cumulative tax reversals sum exactly to the invoice's stored tax
     * (issue #953).
     *
     * @param originalInvoiceId invoice identifier
     * @param status            credit memo status to include (normally POSTED)
     * @return total reversed tax amount
     */
    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(cm.taxAmountReversed), 0) FROM CreditMemo cm"
            + " WHERE cm.originalInvoiceId = :originalInvoiceId AND cm.status = :status")
    java.math.BigDecimal sumTaxReversedAmountByInvoiceIdAndStatus(UUID originalInvoiceId, CreditMemoStatus status);
}
