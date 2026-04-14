package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.JournalEntryLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Repository for Journal Entry Line entity.
 * Supports querying lines by journal entry, GL account, and aggregations for
 * balance checks.
 */
@Repository
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
     * Get current balance for a GL account (sum of all posted debits - credits).
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN jel.debitAmount > 0 THEN jel.debitAmount ELSE -jel.creditAmount END), 0) "
            + "FROM JournalEntryLine jel "
            + "JOIN jel.journalEntry je "
            + "WHERE jel.glAccount.glAccountId = :glAccountId AND je.status = 'POSTED'")
    BigDecimal getAccountBalance(UUID glAccountId);
}
