package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.JournalEntryMapper;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.dto.JournalEntryTraceabilityResponse;
import com.positivity.accounting.internal.dto.UnbalancedEntryException;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.service.GLAccountService;
import com.positivity.accounting.service.JournalEntryService;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class JournalEntryServiceImpl implements JournalEntryService {
    private final Clock clock;

    private final JournalEntryRepository journalEntryRepository;
    private final GLAccountService glAccountService;
    private final AccountingSequenceRepository sequenceRepository;
    private final AccountingSequenceProvisioner sequenceProvisioner;

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
    @Override
    public JournalEntry createJournalEntry(JournalEntry entry) {
        // Generate ID if not present
        if (entry.getJournalEntryId() == null) {
            entry.setJournalEntryId(UUIDv7Generator.generate());
        }
        initializeLineMetadata(entry);

        // Validate balance
        validateBalance(entry);

        // Validate all GL accounts are active at transaction date
        for (JournalEntryLine line : entry.getLines()) {
            glAccountService.validateAccountForPosting(line.getGlAccountId(), entry.getTransactionDate());
        }

        // Set entry metadata
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry.setCreatedAt(Instant.now(clock));
        entry.setUpdatedAt(Instant.now(clock));

        JournalEntry saved = journalEntryRepository.save(entry);
        log.info(
                "Created journal entry {} in DRAFT status with {} lines",
                saved.getJournalEntryId(),
                saved.getLines().size());
        return saved;
    }

    /**
     * Retrieves an existing journal entry by ID.
     *
     * <p>
     * <strong>Note:</strong> Internal callers within this class must use
     * {@link #findById(UUID)} instead, because self-invocation bypasses the
     * Spring proxy and the {@code readOnly} hint would not be applied.
     * </p>
     */
    @Override
    @Transactional(readOnly = true)
    public JournalEntry getJournalEntry(UUID journalEntryId) {
        return findById(journalEntryId);
    }

    @Override
    @Transactional(readOnly = true)
    public JournalEntryTraceabilityResponse getJournalTraceability(UUID journalEntryId) {
        JournalEntry entry = findById(journalEntryId);

        List<JournalEntryResponse> relatedEntries;
        if (entry.getSourceEventId() == null) {
            relatedEntries = List.of(JournalEntryMapper.toResponse(entry));
        } else {
            relatedEntries = journalEntryRepository.findBySourceEvent(entry.getSourceEventId()).stream()
                    .sorted(Comparator.comparing(
                                    JournalEntry::getTransactionDate, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(JournalEntry::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(JournalEntryMapper::toResponse)
                    .toList();
        }

        JournalEntryResponse originalEntry = toResponseOrNull(entry.getReversalJournalEntryId());
        if (originalEntry == null) {
            originalEntry = journalEntryRepository
                    .findByReversalReference(journalEntryId)
                    .map(JournalEntryMapper::toResponse)
                    .orElse(null);
        }

        JournalEntryResponse reversalEntry = toResponseOrNull(entry.getReversedByJournalEntryId());
        if (reversalEntry == null
                && entry.getReversalJournalEntryId() != null
                && (originalEntry == null
                        || !entry.getReversalJournalEntryId().equals(originalEntry.getJournalEntryId()))) {
            reversalEntry = toResponseOrNull(entry.getReversalJournalEntryId());
        }

        return JournalEntryTraceabilityResponse.builder()
                .journalEntryId(entry.getJournalEntryId())
                .sourceEventId(entry.getSourceEventId())
                .journalEntry(JournalEntryMapper.toResponse(entry))
                .originalJournalEntry(originalEntry)
                .reversalJournalEntry(reversalEntry)
                .relatedJournalEntries(relatedEntries)
                .build();
    }

    /**
     * Internal lookup — no transactional annotation so it inherits the caller's
     * transaction context without the proxy-bypass pitfall.
     */
    private JournalEntry findById(UUID journalEntryId) {
        return journalEntryRepository
                .findById(journalEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Journal entry not found: " + journalEntryId));
    }

    private JournalEntryResponse toResponseOrNull(UUID journalEntryId) {
        if (journalEntryId == null) {
            return null;
        }
        return journalEntryRepository
                .findById(journalEntryId)
                .map(JournalEntryMapper::toResponse)
                .orElse(null);
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
    @Override
    public JournalEntry updateJournalEntry(UUID journalEntryId, JournalEntry updates) {
        JournalEntry entry = findById(journalEntryId);

        if (entry.getStatus() != JournalEntryStatus.DRAFT) {
            String msg = "Cannot update " + entry.getStatus() + " journal entry " + journalEntryId;
            log.warn(msg);
            throw new IllegalStateException(msg);
        }

        // Validate updated entry is balanced
        validateBalance(updates);

        // Allow description updates only
        entry.setDescription(updates.getDescription());
        entry.setUpdatedAt(Instant.now(clock));

        // Lines can be updated (add/remove as long as entry remains balanced)
        // For now, disallow line updates; require delete + recreate for complex changes
        if (updates.getLines() != null && !updates.getLines().isEmpty()) {
            // Keep the managed collection instance to avoid orphan-removal dereference
            // exceptions during flush.
            entry.getLines().clear();
            entry.getLines().addAll(updates.getLines());
            initializeLineMetadata(entry);
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
    @Override
    public JournalEntry postJournalEntry(UUID journalEntryId) {
        JournalEntry entry = findById(journalEntryId);

        if (entry.getStatus() != JournalEntryStatus.DRAFT) {
            String msg = "Cannot post " + entry.getStatus() + " journal entry " + journalEntryId;
            log.warn(msg);
            throw new IllegalStateException(msg);
        }

        // Final validation before posting
        validateBalance(entry);
        for (JournalEntryLine line : entry.getLines()) {
            glAccountService.validateAccountForPosting(line.getGlAccountId(), entry.getTransactionDate());
        }

        assignEntryNumber(entry);
        entry.setStatus(JournalEntryStatus.POSTED);
        entry.setPostedAt(Instant.now(clock));
        entry.setUpdatedAt(Instant.now(clock));

        JournalEntry saved = journalEntryRepository.save(entry);
        log.info(
                "Posted journal entry {} as {} with total debits/credits: {}",
                saved.getJournalEntryId(),
                saved.getEntryNumber(),
                saved.getLines().size());
        return saved;
    }

    // ===== POSTED-ENTRY NUMBERING (story A2, issue #942, decision D-1) =====

    /**
     * Assigns the posted-entry number {@code JE-{YYYYMM}-{seq}} from the
     * per-month {@code accounting_sequence} counter.
     *
     * <p>Format decision: the sequence part is an <em>unpadded</em> integer
     * (e.g. {@code JE-202607-1}, {@code JE-202607-12}) — nothing in the repo
     * dictates a padding convention, and a fixed pad would create overflow
     * ambiguity once a month exceeds the padded width. Consumers must not
     * rely on lexicographic ordering of entry numbers within a month.
     *
     * <p>Scope key is {@code JE-{YYYYMM}} derived from the entry's
     * {@code transactionDate} (not {@code postedAt}): a late-posted entry
     * numbers into its transaction month.
     *
     * <p>Transactional design: the counter row is read under
     * {@code FOR UPDATE} and incremented <em>inside the caller's posting
     * transaction</em> — deliberately no {@code REQUIRES_NEW}, in contrast to
     * {@code AccountingPeriodProvisioner} — so a posting rollback rolls the
     * increment back too and the number is never consumed. Post-time
     * assignment in the same transaction as the status flip is what makes the
     * numbering gapless as a side effect (D-1; no statutory guarantee
     * claimed). Only the zero-consumption first-use bootstrap of the counter
     * row runs isolated (see {@link AccountingSequenceProvisioner}).
     *
     * <p>Shared seam: also intended for reversal numbering when story A3
     * rewrites {@code reverseJournalEntry} — reversals get their own numbers.
     *
     * @param entry the entry being transitioned to POSTED in the current
     *              transaction; its {@code entryNumber} is set as a side
     *              effect
     */
    private void assignEntryNumber(JournalEntry entry) {
        String scopeKey = entryNumberScopeKey(entry.getTransactionDate());
        AccountingSequence sequence =
                sequenceRepository.findByScopeKey(scopeKey).orElseGet(() -> provisionAndRelock(scopeKey));
        long assigned = sequence.getNextValue();
        sequence.setNextValue(assigned + 1);
        entry.setEntryNumber(scopeKey + "-" + assigned);
    }

    /**
     * First use of a month scope: bootstrap the counter row in an isolated
     * transaction ({@link AccountingSequenceProvisioner}), then lock it in
     * the current posting transaction. A concurrent bootstrapper losing the
     * unique-key race falls through to the locked re-read of the winner's
     * committed row.
     */
    private AccountingSequence provisionAndRelock(String scopeKey) {
        try {
            sequenceProvisioner.provision(scopeKey);
        } catch (DataIntegrityViolationException raceLost) {
            log.debug("Lost accounting_sequence bootstrap race for scope {}; re-reading winner's row", scopeKey);
        }
        return sequenceRepository
                .findByScopeKey(scopeKey)
                .orElseThrow(() ->
                        new IllegalStateException("accounting_sequence row missing after bootstrap: " + scopeKey));
    }

    /**
     * Sequence scope key {@code JE-{YYYYMM}} for a transaction date.
     */
    private static String entryNumberScopeKey(LocalDateTime transactionDate) {
        return String.format("JE-%04d%02d", transactionDate.getYear(), transactionDate.getMonthValue());
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
    @Override
    public JournalEntry reverseJournalEntry(UUID originalEntryId, String reversalReason) {
        JournalEntry original = findById(originalEntryId);

        if (original.getStatus() != JournalEntryStatus.POSTED) {
            String msg = "Cannot reverse " + original.getStatus() + " entry; only POSTED entries can be reversed";
            log.warn(msg);
            throw new IllegalArgumentException(msg);
        }

        // Create reversal entry with inverted debits/credits
        JournalEntry reversal = new JournalEntry();
        reversal.setJournalEntryId(UUIDv7Generator.generate());
        reversal.setTransactionDate(LocalDateTime.now(clock));
        reversal.setDescription("REVERSAL of " + original.getJournalEntryId() + " - Reason: " + reversalReason);
        reversal.setSourceEventId(original.getSourceEventId());
        reversal.setStatus(JournalEntryStatus.POSTED); // Reversals post immediately
        reversal.setPostedAt(Instant.now(clock));
        reversal.setCreatedAt(Instant.now(clock));
        reversal.setUpdatedAt(Instant.now(clock));

        // Invert all lines: debits become credits and vice versa
        List<JournalEntryLine> reversalLines = new java.util.ArrayList<>();
        for (JournalEntryLine line : original.getLines()) {
            JournalEntryLine reversalLine = new JournalEntryLine();
            reversalLine.setLineId(UUIDv7Generator.generate());
            reversalLine.setJournalEntry(reversal);
            reversalLine.setGlAccountId(line.getGlAccountId());
            reversalLine.setDebitAmount(line.getCreditAmount()); // Swap
            reversalLine.setCreditAmount(line.getDebitAmount()); // Swap
            reversalLine.setDescription("Reversal of line " + line.getLineId());
            reversalLines.add(reversalLine);
        }
        reversal.setLines(reversalLines);
        initializeLineMetadata(reversal);

        JournalEntry saved = journalEntryRepository.save(reversal);
        log.info("Created reversal entry {} for original entry {}", saved.getJournalEntryId(), originalEntryId);
        return saved;
    }

    /**
     * Lists journal entries with pagination and optional exact-match
     * entry-number filtering.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<JournalEntry> listJournalEntries(Pageable pageable, @Nullable String entryNumber) {
        if (entryNumber == null || entryNumber.isBlank()) {
            return journalEntryRepository.findAll(pageable);
        }
        return journalEntryRepository.findByEntryNumber(entryNumber, pageable);
    }

    /**
     * Find all posted entries for audit or reconciliation.
     */
    @Override
    public Page<JournalEntry> listPostedEntries(Pageable pageable) {
        return journalEntryRepository.findByStatus(JournalEntryStatus.POSTED, pageable);
    }

    /**
     * Find entries by status (DRAFT, POSTED, REVERSED).
     */
    @Override
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
            throw new UnbalancedEntryException(msg);
        }

        log.debug("Journal entry balance valid: debits={}, credits={}", totalDebits, totalCredits);
    }

    private void initializeLineMetadata(JournalEntry entry) {
        if (entry.getLines() == null || entry.getLines().isEmpty()) {
            return;
        }
        int lineNumber = 1;
        for (JournalEntryLine line : entry.getLines()) {
            if (line.getJournalEntry() == null) {
                line.setJournalEntry(entry);
            }
            if (line.getLineNumber() == null) {
                line.setLineNumber(lineNumber);
            }
            lineNumber++;
        }
    }
}
