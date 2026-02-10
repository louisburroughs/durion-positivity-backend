package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Journal Entry entity.
 * Supports CRUD, status queries, and transaction date range searches.
 */
@Repository
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
     * Find journal entries for a transaction date range.
     */
    @Query("SELECT je FROM JournalEntry je WHERE je.transactionDate >= :startDate AND je.transactionDate <= :endDate ORDER BY je.transactionDate DESC")
    List<JournalEntry> findByTransactionDateRange(LocalDateTime startDate, LocalDateTime endDate);

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
     * Find journal entries reversed by another entry.
     */
    @Query("SELECT je FROM JournalEntry je WHERE je.reversalJournalEntryId = :reversalId")
    Optional<JournalEntry> findByReversalReference(UUID reversalId);

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
     * @param accountId GL account ID
     * @param startDate period start date
     * @param endDate   period end date
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
                  AND jel.accountId = :accountId
                  AND je.transactionDate >= :startDate
                  AND je.transactionDate <= :endDate
            """)
    java.math.BigDecimal sumPostedBalanceForAccount(String accountId, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * Sum net balance for a GL account as of a specific date (POSTED entries only).
     * Used for balance sheet generation.
     *
     * @param accountId GL account ID
     * @param asOfDate  reporting date (inclusive)
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
                  AND jel.accountId = :accountId
                  AND je.transactionDate <= :asOfDate
            """)
    java.math.BigDecimal sumPostedBalanceAsOf(String accountId, LocalDateTime asOfDate);

    /**
     * Find all POSTED journal lines for a GL account within date range.
     * Used for drilldown reporting.
     *
     * @param accountId GL account ID
     * @param startDate period start date
     * @param endDate   period end date
     * @return list of journal entries with lines for this account
     */
    @Query("""
                SELECT DISTINCT je
                FROM JournalEntry je
                JOIN FETCH je.lines jel
                WHERE je.status = 'POSTED'
                  AND jel.accountId = :accountId
                  AND je.transactionDate >= :startDate
                  AND je.transactionDate <= :endDate
                ORDER BY je.transactionDate DESC
            """)
    List<JournalEntry> findPostedEntriesForAccount(String accountId, LocalDateTime startDate, LocalDateTime endDate);
}
