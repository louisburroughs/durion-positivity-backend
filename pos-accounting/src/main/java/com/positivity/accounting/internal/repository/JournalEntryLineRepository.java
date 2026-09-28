package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.JournalEntryLine;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for Journal Entry Line entity.
 * Supports querying lines by journal entry, GL account, and aggregations for
 * balance checks.
 */
public interface JournalEntryLineRepository extends JpaRepository<JournalEntryLine, UUID> {

    /**
     * Find all lines for a journal entry.
     */
    List<JournalEntryLine> findByJournalEntry_JournalEntryId(UUID journalEntryId);

    /**
     * Find all lines posting to a specific GL account.
     */
    @Query("SELECT jel FROM JournalEntryLine jel WHERE jel.glAccount.glAccountId = :glAccountId")
    List<JournalEntryLine> findByGLAccount(UUID glAccountId);

    /**
     * Calculate total debits for a journal entry.
     */
    @Query(
            "SELECT COALESCE(SUM(jel.debitAmount), 0) FROM JournalEntryLine jel WHERE jel.journalEntry.journalEntryId = :journalEntryId")
    BigDecimal sumDebitsByJournalEntry(UUID journalEntryId);

    /**
     * Calculate total credits for a journal entry.
     */
    @Query(
            "SELECT COALESCE(SUM(jel.creditAmount), 0) FROM JournalEntryLine jel WHERE jel.journalEntry.journalEntryId = :journalEntryId")
    BigDecimal sumCreditsByJournalEntry(UUID journalEntryId);

    /**
     * Get current balance for a GL account: debits − credits over ledger entries, POSTED and
     * REVERSED, never DRAFT (issue #2308). A reversed original and its POSTED reversal net to zero.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN jel.debitAmount > 0 THEN jel.debitAmount ELSE -jel.creditAmount END), 0) "
            + "FROM JournalEntryLine jel "
            + "JOIN jel.journalEntry je "
            + "WHERE jel.glAccount.glAccountId = :glAccountId AND je.status IN ('POSTED', 'REVERSED')")
    BigDecimal getAccountBalance(UUID glAccountId);

    /**
     * Signed GL balance for an account as-of an instant: sum of (debit − credit) over the lines of
     * every entry that was posted — {@code POSTED} or {@code REVERSED}, never {@code DRAFT} — whose
     * transaction date is on or before {@code asOf}. A reversal is its own POSTED entry dated at the
     * reversal, so a reversed pair counts the original from its date and the inverse from the
     * reversal's, and nothing already reported changes (SPEC-manual-bank-reconciliation §3.7, G15;
     * story S4, #2303 — the counterpart of #2308's report fix). The bank reconciliation is the only
     * caller; it passes the end of a day at microsecond precision (Postgres {@code timestamp(6)}).
     */
    @Query("SELECT COALESCE(SUM(jel.debitAmount) - SUM(jel.creditAmount), 0) "
            + "FROM JournalEntryLine jel "
            + "JOIN jel.journalEntry je "
            + "WHERE jel.glAccount.glAccountId = :glAccountId AND je.status IN ('POSTED', 'REVERSED') "
            + "AND je.transactionDate <= :asOf")
    BigDecimal getAccountBalanceAsOf(@Param("glAccountId") UUID glAccountId, @Param("asOf") LocalDateTime asOf);

    /**
     * Find posted journal entry lines for a set of GL accounts whose entry transaction date falls
     * within the given range. The parent entry is fetched so callers can read its transaction date and
     * each line's dimensions without triggering lazy loads.
     *
     * <p>Used by the CAP-316 Labor &amp; Overhead report to gather contributing postings; callers
     * bucket by month and filter by the {@code locationId} dimension in Java (the JSON dimensions map
     * is not portably queryable across H2/PostgreSQL).
     *
     * @param accountIds GL account ids to include (caller skips the query when empty)
     * @param start inclusive lower bound on the entry transaction date
     * @param end inclusive upper bound on the entry transaction date
     * @return ledger lines (POSTED and REVERSED entries, never DRAFT; issue #2308) for those
     *     accounts in the date range
     */
    @Query("SELECT jel FROM JournalEntryLine jel "
            + "JOIN FETCH jel.journalEntry je "
            + "WHERE je.status IN ('POSTED', 'REVERSED') "
            + "AND jel.glAccount.glAccountId IN :accountIds "
            + "AND je.transactionDate >= :start "
            + "AND je.transactionDate <= :end")
    List<JournalEntryLine> findPostedLinesByAccountsAndDateRange(
            @Param("accountIds") Collection<UUID> accountIds,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
