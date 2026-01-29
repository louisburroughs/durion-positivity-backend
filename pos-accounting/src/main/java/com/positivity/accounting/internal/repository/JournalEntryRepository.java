package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Journal Entry entity.
 * Supports CRUD, status queries, and transaction date range searches.
 */
@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, String> {

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
    List<JournalEntry> findByTransactionDateRange(LocalDate startDate, LocalDate endDate);

    /**
     * Find posted journal entries with pagination.
     */
    @Query("SELECT je FROM JournalEntry je WHERE je.status = 'POSTED' ORDER BY je.postedAt DESC")
    Page<JournalEntry> findPostedEntries(Pageable pageable);

    /**
     * Find journal entries by source event (for traceability).
     */
    @Query("SELECT je FROM JournalEntry je WHERE je.sourceEventId = :sourceEventId")
    List<JournalEntry> findBySourceEvent(String sourceEventId);

    /**
     * Find journal entries reversed by another entry.
     */
    @Query("SELECT je FROM JournalEntry je WHERE je.reversalJournalEntryId = :reversalId")
    Optional<JournalEntry> findByReversalReference(String reversalId);

    /**
     * Find draft entries for an organization (for editing).
     */
    @Query("SELECT je FROM JournalEntry je WHERE je.status = 'DRAFT' ORDER BY je.createdAt DESC")
    Page<JournalEntry> findDraftEntries(Pageable pageable);
}
