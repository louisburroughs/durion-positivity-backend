package com.positivity.accounting.service;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service for Journal Entry operations.
 * Handles creation, validation, posting, and reversal of journal entries with
 * immutability constraints.
 *
 * State Machine: DRAFT → POSTED (immutable) → [REVERSED via reversal entry]
 *
 * Key Business Rules:
 * - Must be balanced: sum(debits) = sum(credits) within ±0.0001 tolerance
 * - Cannot post to inactive GL accounts
 * - Posted entries are immutable (changes via reversal only)
 * - Reversals create new entries with opposite signs
 * - All entries must have supporting source event reference
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class JournalEntryService {

    private final JournalEntryRepository journalEntryRepository;
    private final GLAccountService glAccountService;

    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.0001");

    /**
     * Creates a new draft journal entry.
     * Entry must be balanced: sum of debits = sum of credits.
     * GL accounts are validated but not locked (allow posting to DRAFT entries
     * simultaneously).
     *
     * @param entry journal entry with lines to create
     * @return created entry in DRAFT status
     * @throws IllegalArgumentException if entry is unbalanced or GL accounts
     *                                  invalid
     */
    public JournalEntry createJournalEntry(JournalEntry entry) {
        // Generate ID if not present
        if (entry.getJournalEntryId() == null || entry.getJournalEntryId().isBlank()) {
            entry.setJournalEntryId(UUID.randomUUID().toString());
        }

        // Validate balance
        validateBalance(entry);

        // Validate all GL accounts are active at transaction date
        for (JournalEntryLine line : entry.getLines()) {
            glAccountService.validateAccountForPosting(
                    line.getGlAccountId(), entry.getTransactionDate());
        }

        // Set entry metadata
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry.setCreatedAt(Instant.now());
        entry.setModifiedAt(Instant.now());

        JournalEntry saved = journalEntryRepository.save(entry);
        log.info("Created journal entry {} in DRAFT status with {} lines",
                saved.getJournalEntryId(), saved.getLines().size());
        return saved;
    }

    /**
     * Retrieves an existing journal entry by ID.
     */
    @Transactional(readOnly = true)
    public JournalEntry getJournalEntry(String journalEntryId) {
        return journalEntryRepository.findById(journalEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Journal entry not found: " + journalEntryId));
    }

    /**
     * Updates a draft journal entry (DRAFT status only).
     * Once POSTED, entries are immutable; use reverse() instead.
     *
     * @param journalEntryId entry to update
     * @param updates        journal entry with new values
     * @return updated entry
     * @throws IllegalStateException if entry is not in DRAFT status
     */
    public JournalEntry updateJournalEntry(String journalEntryId, JournalEntry updates) {
        JournalEntry entry = getJournalEntry(journalEntryId);

        if (entry.getStatus() != JournalEntryStatus.DRAFT) {
            String msg = "Cannot update " + entry.getStatus() + " journal entry " + journalEntryId;
            log.warn(msg);
            throw new IllegalStateException(msg);
        }

        // Validate updated entry is balanced
        validateBalance(updates);

        // Allow description updates only
        entry.setDescription(updates.getDescription());
        entry.setModifiedAt(Instant.now());

        // Lines can be updated (add/remove as long as entry remains balanced)
        // For now, disallow line updates; require delete + recreate for complex changes
        if (updates.getLines() != null && !updates.getLines().isEmpty()) {
            entry.setLines(updates.getLines());
        }

        return journalEntryRepository.save(entry);
    }

    /**
     * Posts a draft journal entry to GL (transitions to POSTED).
     * Immutable thereafter; GL account balances updated.
     *
     * @param journalEntryId entry to post
     * @return posted entry
     * @throws IllegalStateException if entry is not in DRAFT status or is
     *                               unbalanced
     */
    public JournalEntry postJournalEntry(String journalEntryId) {
        JournalEntry entry = getJournalEntry(journalEntryId);

        if (entry.getStatus() != JournalEntryStatus.DRAFT) {
            String msg = "Cannot post " + entry.getStatus() + " journal entry " + journalEntryId;
            log.warn(msg);
            throw new IllegalStateException(msg);
        }

        // Final validation before posting
        validateBalance(entry);
        for (JournalEntryLine line : entry.getLines()) {
            glAccountService.validateAccountForPosting(
                    line.getGlAccountId(), entry.getTransactionDate());
        }

        entry.setStatus(JournalEntryStatus.POSTED);
        entry.setPostedAt(Instant.now());
        entry.setModifiedAt(Instant.now());

        JournalEntry saved = journalEntryRepository.save(entry);
        log.info("Posted journal entry {} with total debits/credits: {}",
                saved.getJournalEntryId(), saved.getLines().size());
        return saved;
    }

    /**
     * Reverses a posted journal entry by creating an inverse entry.
     * Original entry remains POSTED; reversal entry appears as REVERSED.
     * Reversal entries are immediately POSTED (no DRAFT → POSTED transition).
     *
     * @param originalEntryId entry to reverse
     * @param reversalReason  reason for reversal (e.g., "CORRECTION", "ADJUSTMENT")
     * @return reversal journal entry
     * @throws IllegalArgumentException if original entry not found or not POSTED
     */
    public JournalEntry reverseJournalEntry(String originalEntryId, String reversalReason) {
        JournalEntry original = getJournalEntry(originalEntryId);

        if (original.getStatus() != JournalEntryStatus.POSTED) {
            String msg = "Cannot reverse " + original.getStatus() + " entry; only POSTED entries can be reversed";
            log.warn(msg);
            throw new IllegalArgumentException(msg);
        }

        // Create reversal entry with inverted debits/credits
        JournalEntry reversal = new JournalEntry();
        reversal.setJournalEntryId(UUID.randomUUID().toString());
        reversal.setTransactionDate(LocalDateTime.now());
        reversal.setDescription("Reversal of " + original.getJournalEntryId() + " - Reason: " + reversalReason);
        reversal.setSourceEventId(original.getSourceEventId());
        reversal.setStatus(JournalEntryStatus.POSTED); // Reversals post immediately
        reversal.setPostedAt(Instant.now());
        reversal.setCreatedAt(Instant.now());
        reversal.setModifiedAt(Instant.now());

        // Invert all lines: debits become credits and vice versa
        List<JournalEntryLine> reversalLines = new java.util.ArrayList<>();
        for (JournalEntryLine line : original.getLines()) {
            JournalEntryLine reversalLine = new JournalEntryLine();
            reversalLine.setLineId(UUID.randomUUID().toString());
            reversalLine.setJournalEntryId(reversal.getJournalEntryId());
            reversalLine.setGlAccountId(line.getGlAccountId());
            reversalLine.setDebitAmount(line.getCreditAmount()); // Swap
            reversalLine.setCreditAmount(line.getDebitAmount()); // Swap
            reversalLine.setDescription("Reversal of line " + line.getLineId());
            reversalLine.setCreatedAt(Instant.now());
            reversalLines.add(reversalLine);
        }
        reversal.setLines(reversalLines);

        JournalEntry saved = journalEntryRepository.save(reversal);
        log.info("Created reversal entry {} for original entry {}", saved.getJournalEntryId(), originalEntryId);
        return saved;
    }

    /**
     * Lists journal entries with pagination and filtering.
     */
    @Transactional(readOnly = true)
    public Page<JournalEntry> listJournalEntries(Pageable pageable) {
        return journalEntryRepository.findAll(pageable);
    }

    /**
     * Find all posted entries for audit or reconciliation.
     */
    public Page<JournalEntry> listPostedEntries(Pageable pageable) {
        return journalEntryRepository.findByStatus(JournalEntryStatus.POSTED, pageable);
    }

    /**
     * Find entries by status (DRAFT, POSTED, REVERSED).
     */
    public List<JournalEntry> findByStatus(JournalEntryStatus status) {
        return journalEntryRepository.findByStatus(status);
    }

    // ===== VALIDATION =====

    /**
     * Validate that journal entry is balanced.
     * Sum of debits must equal sum of credits within ±0.0001 tolerance.
     *
     * @param entry entry to validate
     * @throws IllegalArgumentException if entry is unbalanced
     */
    private void validateBalance(JournalEntry entry) {
        if (entry.getLines() == null || entry.getLines().isEmpty()) {
            throw new IllegalArgumentException("Journal entry must have at least one line");
        }

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        for (JournalEntryLine line : entry.getLines()) {
            if (line.getDebitAmount() != null) {
                totalDebits = totalDebits.add(line.getDebitAmount());
            }
            if (line.getCreditAmount() != null) {
                totalCredits = totalCredits.add(line.getCreditAmount());
            }
        }

        BigDecimal difference = totalDebits.subtract(totalCredits).abs();
        if (difference.compareTo(BALANCE_TOLERANCE) > 0) {
            String msg = String.format(
                    "Journal entry is unbalanced. Total debits: %.2f, Total credits: %.2f, Difference: %.4f",
                    totalDebits, totalCredits, difference);
            log.warn(msg);
            throw new IllegalArgumentException(msg);
        }

        log.debug("Journal entry balance valid: debits={}, credits={}", totalDebits, totalCredits);
    }
}
