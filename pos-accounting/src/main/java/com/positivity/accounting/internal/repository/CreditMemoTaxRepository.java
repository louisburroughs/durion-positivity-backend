package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.CreditMemoTax;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for the per-jurisdiction credit-memo tax attribution written at credit-memo
 * creation (issue #996).
 */
public interface CreditMemoTaxRepository extends JpaRepository<CreditMemoTax, UUID> {

    /**
     * Attribution rows for a single credit memo.
     *
     * @param creditMemoId credit memo identifier
     * @return the credit's per-jurisdiction reversals (empty for pre-#996 credits)
     */
    List<CreditMemoTax> findByCreditMemoId(UUID creditMemoId);

    /**
     * Attribution rows for a batch of credit memos, so the T8 report can load a whole
     * period's credit side in one query.
     *
     * @param creditMemoIds credit memo identifiers (must be non-empty)
     * @return matching attribution rows (unordered)
     */
    List<CreditMemoTax> findByCreditMemoIdIn(List<UUID> creditMemoIds);

    /**
     * Tax already reversed in a jurisdiction across every non-DRAFT credit memo of an
     * invoice. Drives the final-credit residual so cumulative per-jurisdiction reversals
     * land exactly on that jurisdiction's collected tax.
     *
     * @param originalInvoiceId the credited invoice
     * @param jurisdictionType  jurisdiction level (STATE/COUNTY/CITY/SPECIAL)
     * @param jurisdictionCode  jurisdiction code
     * @return sum of previously reversed tax in that jurisdiction (zero when none)
     */
    @Query("""
            SELECT COALESCE(SUM(cmt.taxAmountReversed), 0)
            FROM CreditMemoTax cmt, CreditMemo cm
            WHERE cmt.creditMemoId = cm.creditMemoId
              AND cm.originalInvoiceId = :originalInvoiceId
              AND cm.status <> com.positivity.accounting.internal.enums.CreditMemoStatus.DRAFT
              AND cmt.jurisdictionType = :jurisdictionType
              AND cmt.jurisdictionCode = :jurisdictionCode
            """)
    java.math.BigDecimal sumReversedForInvoiceJurisdiction(
            UUID originalInvoiceId, String jurisdictionType, String jurisdictionCode);
}
