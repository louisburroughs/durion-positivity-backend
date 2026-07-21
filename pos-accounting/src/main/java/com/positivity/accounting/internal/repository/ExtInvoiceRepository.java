package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtInvoice;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtInvoiceRepository extends JpaRepository<ExtInvoice, UUID> {

    /**
     * Find replicated invoices whose finalization (accrual posting) timestamp falls in
     * the inclusive instant range. Used by the Sales-Tax Liability report (story T8,
     * issue #966) to bucket invoice tax by the invoice's posting period.
     *
     * @param start inclusive lower bound (start-of-day of the period start, UTC)
     * @param end   inclusive upper bound (end-of-day of the period end, UTC)
     * @return invoices finalized within the range (unordered)
     */
    List<ExtInvoice> findByFinalizedAtBetween(Instant start, Instant end);

    /**
     * Find replicated invoices whose (pos-invoice) lifecycle status is one of the
     * given values. Used by the Aged Receivables report (story G2, issue #960) to
     * load AR-eligible invoices ({@code FINALIZED} / {@code POSTED}); the open
     * balance per invoice is then derived in-service via
     * {@code InvoiceBalanceCalculator}.
     *
     * @param statuses lifecycle status values to include
     * @return matching invoices (unordered; the report orders by customer)
     */
    List<ExtInvoice> findByStatusIn(Collection<String> statuses);
}
