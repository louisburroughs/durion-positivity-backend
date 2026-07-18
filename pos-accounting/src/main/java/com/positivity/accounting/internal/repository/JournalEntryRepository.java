package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for Journal Entry entity.
 * Supports CRUD, status queries, and transaction date range searches.
 */
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    /**
     * Find journal entries by status (DRAFT, POSTED, REVERSED).
     */
    List<JournalEntry> findByStatus(JournalEntryStatus status);

    /**
     * Find journal entries by status with pagination.
     */
    Page<JournalEntry> findByStatus(JournalEntryStatus status, Pageable pageable);

    /**
     * Find journal entries by exact posted-entry number
     * ({@code JE-&#123;YYYYMM&#125;-&#123;seq&#125;}) with pagination. The column is
     * unique, so at most one row matches; paginated for list-endpoint symmetry.
     */
    Page<JournalEntry> findByEntryNumber(String entryNumber, Pageable pageable);

    /**
     * Find journal entries for a transaction date range.
     */
    @Query(
            "SELECT je FROM JournalEntry je WHERE je.transactionDate >= :startDate AND je.transactionDate <= :endDate ORDER BY je.transactionDate DESC")
    List<JournalEntry> findByTransactionDateRange(LocalDateTime startDate, LocalDateTime endDate);

    /**
     * Find journal entries with a given status dated inside a half-open
     * transaction-date range [startDate, endDateExclusive). Used by the
     * accounting-period close gate (DRAFT entries block close, story B1).
     */
    @Query(
            "SELECT je FROM JournalEntry je WHERE je.status = :status AND je.transactionDate >= :startDate AND je.transactionDate < :endDateExclusive ORDER BY je.transactionDate ASC")
    List<JournalEntry> findByStatusAndTransactionDateInRange(
            JournalEntryStatus status, LocalDateTime startDate, LocalDateTime endDateExclusive);

    /**
     * Find posted journal entries with pagination.
     */
    @Query("SELECT je FROM JournalEntry je WHERE je.status = 'POSTED' ORDER BY je.postedAt DESC")
    Page<JournalEntry> findPostedEntries(Pageable pageable);

    /**
     * Find journal entries by source event (for traceability).
     */
    @Query("SELECT je FROM JournalEntry je WHERE je.sourceEventId = :sourceEventId")
    List<JournalEntry> findBySourceEvent(UUID sourceEventId);

    /**
     * Find the reversal entry of a given original entry: the entry whose
     * {@code reversalJournalEntry} link ("the entry I reverse") points at
     * {@code originalId}. At most one row can match because an entry is
     * reversible only once (guarded by {@link #markReversed}).
     */
    @Query("SELECT je FROM JournalEntry je WHERE je.reversalJournalEntry.journalEntryId = :originalId")
    Optional<JournalEntry> findByReversalReference(UUID originalId);

    /**
     * Atomically transition a POSTED entry to REVERSED, stamping the reversal
     * linkage, {@code reversedAt}, and audit columns (story A3, issue #943).
     *
     * <p>The {@code status = POSTED} predicate is the concurrency guard for
     * the double-reversal race: two competing reversal transactions both pass
     * the service-level status check, but only one UPDATE matches the row —
     * the loser gets an update count of 0 and must abort (409). A bulk update
     * bypasses entity callbacks ({@code @PreUpdate}/auditing), so
     * {@code updatedAt}/{@code modifiedBy} are set explicitly here.
     *
     * @param journalEntryId original entry to flip
     * @param reversal       the already-persisted reversal entry (FK target of
     *                       {@code reversedByJournalEntry})
     * @param reversedAt     reversal instant (also used for {@code updatedAt})
     * @param actor          acting user recorded in {@code modifiedBy}
     * @return number of rows updated: 1 on success, 0 when the entry was no
     *         longer POSTED (concurrent reversal won the race)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "UPDATE JournalEntry je SET je.status = com.positivity.accounting.internal.enums.JournalEntryStatus.REVERSED,"
                    + " je.reversedByJournalEntry = :reversal, je.reversedAt = :reversedAt, je.updatedAt = :reversedAt,"
                    + " je.modifiedBy = :actor WHERE je.journalEntryId = :journalEntryId"
                    + " AND je.status = com.positivity.accounting.internal.enums.JournalEntryStatus.POSTED")
    int markReversed(UUID journalEntryId, JournalEntry reversal, Instant reversedAt, String actor);

    /**
     * Find draft entries for an organization (for editing).
     */
    @Query("SELECT je FROM JournalEntry je WHERE je.status = 'DRAFT' ORDER BY je.createdAt DESC")
    Page<JournalEntry> findDraftEntries(Pageable pageable);

    /**
     * Sum net balance (debits - credits) for a GL account within date range (POSTED
     * entries only).
     * Used for financial reporting.
     *
     * @param glAccountId GL account ID (UUID)
     * @param startDate   period start date
     * @param endDate     period end date
     * @return net balance (sum of debits - sum of credits), or 0 if no entries
     */
    @Query("""
                SELECT COALESCE(
                    SUM(CASE WHEN jel.debitAmount IS NOT NULL THEN jel.debitAmount ELSE 0 END) -
                    SUM(CASE WHEN jel.creditAmount IS NOT NULL THEN jel.creditAmount ELSE 0 END),
                    0
                )
                FROM JournalEntry je
                JOIN je.lines jel
                WHERE je.status = 'POSTED'
                  AND jel.glAccount.glAccountId = :glAccountId
                  AND je.transactionDate >= :startDate
                  AND je.transactionDate <= :endDate
            """)
    java.math.BigDecimal sumPostedBalanceForAccount(UUID glAccountId, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * Sum net balance for a GL account as of a specific date (POSTED entries only).
     * Used for balance sheet generation.
     *
     * @param glAccountId GL account ID (UUID)
     * @param asOfDate    reporting date (inclusive)
     * @return net balance (sum of debits - sum of credits), or 0 if no entries
     */
    @Query("""
                SELECT COALESCE(
                    SUM(CASE WHEN jel.debitAmount IS NOT NULL THEN jel.debitAmount ELSE 0 END) -
                    SUM(CASE WHEN jel.creditAmount IS NOT NULL THEN jel.creditAmount ELSE 0 END),
                    0
                )
                FROM JournalEntry je
                JOIN je.lines jel
                WHERE je.status = 'POSTED'
                  AND jel.glAccount.glAccountId = :glAccountId
                  AND je.transactionDate <= :asOfDate
            """)
    java.math.BigDecimal sumPostedBalanceAsOf(UUID glAccountId, LocalDateTime asOfDate);

    /**
     * Find all POSTED journal lines for a GL account within date range.
     * Used for drilldown reporting.
     *
     * @param glAccountId GL account ID (UUID)
     * @param startDate   period start date
     * @param endDate     period end date
     * @return list of journal entries with lines for this account
     */
    @Query("""
                SELECT DISTINCT je
                FROM JournalEntry je
                JOIN FETCH je.lines jel
                WHERE je.status = 'POSTED'
                  AND jel.glAccount.glAccountId = :glAccountId
                  AND je.transactionDate >= :startDate
                  AND je.transactionDate <= :endDate
                ORDER BY je.transactionDate DESC
            """)
    List<JournalEntry> findPostedEntriesForAccount(UUID glAccountId, LocalDateTime startDate, LocalDateTime endDate);
}
