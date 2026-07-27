package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtInvoice;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * Load one replicated invoice under a row-level write lock held for the rest of the
     * caller's transaction (issue #992).
     *
     * <p>The invoice's AR balance is <em>derived</em> from accounting's own records
     * ({@code InvoiceBalanceCalculator}), so concurrent writers against the same invoice do
     * not conflict on any single row and cannot see each other's uncommitted work. Two
     * customer-credit applications of the full balance would therefore both pass the
     * balance-due gate and both commit, crediting AR twice for one receivable. Taking a write
     * lock on the replica row makes "read the derived balance, then insert the draw-down"
     * atomic per invoice.
     *
     * <p>Locking the replica is safe: the invoice-events listener updates this row, so a
     * concurrent projection write waits rather than interleaving.
     *
     * @param invoiceId invoice identifier
     * @return the locked invoice, if it exists in the replica
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ExtInvoice i WHERE i.invoiceId = :invoiceId")
    Optional<ExtInvoice> findByIdForUpdate(UUID invoiceId);
}
