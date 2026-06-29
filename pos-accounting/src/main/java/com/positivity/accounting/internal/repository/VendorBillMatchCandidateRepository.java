package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.VendorBillMatchCandidate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for vendor bill match candidates.
 * Used when ambiguous matching produces multiple bill candidates for a single
 * invoice.
 */
public interface VendorBillMatchCandidateRepository extends JpaRepository<VendorBillMatchCandidate, UUID> {

    /**
     * Find all unresolved candidates for a given invoice event.
     *
     * @param invoiceEventId the invoice event that triggered the ambiguous match
     * @return candidates ordered by score descending
     */
    List<VendorBillMatchCandidate> findByInvoiceEventIdAndResolvedFalseOrderByMatchScoreDesc(UUID invoiceEventId);

    /**
     * Find all candidates (resolved and unresolved) for a given invoice event.
     *
     * @param invoiceEventId the invoice event that triggered the ambiguous match
     * @return all candidates ordered by score descending
     */
    List<VendorBillMatchCandidate> findByInvoiceEventIdOrderByMatchScoreDesc(UUID invoiceEventId);

    /**
     * Find all unresolved candidates for a given vendor bill.
     *
     * @param vendorBillId the vendor bill ID
     * @return candidates for the specified bill
     */
    List<VendorBillMatchCandidate> findByVendorBill_VendorBillIdAndResolvedFalse(UUID vendorBillId);
}
