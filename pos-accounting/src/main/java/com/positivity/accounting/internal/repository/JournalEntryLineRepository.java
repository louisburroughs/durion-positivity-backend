package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.JournalEntryLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Repository for Journal Entry Line entity.
 * Supports querying lines by journal entry, GL account, and aggregations for
 * balance checks.
 */
@Repository
public interface JournalEntryLineRepository extends JpaRepository<JournalEntryLine, String> {

    /**
     * Find all lines for a journal entry.
     */
    List<JournalEntryLine> findByJournalEntryId(String journalEntryId);

    /**
     * Find all lines posting to a specific GL account.
     */
    @Query("SELECT jel FROM JournalEntryLine jel WHERE jel.glAccountId = :glAccountId")
    List<JournalEntryLine> findByGLAccount(String glAccountId);

    /**
     * Calculate total debits for a journal entry.
     */
    @Query("SELECT COALESCE(SUM(jel.debitAmount), 0) FROM JournalEntryLine jel WHERE jel.journalEntryId = :journalEntryId")
    BigDecimal sumDebitsByJournalEntry(String journalEntryId);

    /**
     * Calculate total credits for a journal entry.
     */
    @Query("SELECT COALESCE(SUM(jel.creditAmount), 0) FROM JournalEntryLine jel WHERE jel.journalEntryId = :journalEntryId")
    BigDecimal sumCreditsByJournalEntry(String journalEntryId);

    /**
     * Get current balance for a GL account (sum of all posted debits - credits).
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN jel.debitAmount > 0 THEN jel.debitAmount ELSE -jel.creditAmount END), 0) " +
            "FROM JournalEntryLine jel " +
            "JOIN JournalEntry je ON jel.journalEntryId = je.journalEntryId " +
            "WHERE jel.glAccountId = :glAccountId AND je.status = 'POSTED'")
    BigDecimal getAccountBalance(String glAccountId);
}
