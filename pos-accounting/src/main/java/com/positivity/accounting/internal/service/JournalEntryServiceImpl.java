package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.JournalEntryMapper;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.dto.JournalEntryTraceabilityResponse;
import com.positivity.accounting.internal.dto.UnbalancedEntryException;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.event.JournalEntryReversed;
import com.positivity.accounting.internal.exception.JournalEntryNotReversibleException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.service.AccountingPeriodService;
import com.positivity.accounting.service.GLAccountService;
import com.positivity.accounting.service.JournalEntryService;
import com.positivity.accounting.service.OutboxService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
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
    private final AccountingPeriodService accountingPeriodService;
    private final AccountingPeriodGate accountingPeriodGate;
    private final AccountingAuditLogRepository auditLogRepository;
    private final OutboxService outboxService;

    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.0001");
    private static final String SYSTEM = "SYSTEM";
    private static final String AUDIT_ENTITY_TYPE_JOURNAL_ENTRY = "JOURNAL_ENTRY";
    private static final String AUDIT_OPERATION_REVERSE = "REVERSE";
    private static final String AGGREGATE_TYPE_JOURNAL_ENTRY = "JournalEntry";
    private static final String EVENT_TYPE_JOURNAL_ENTRY_REVERSED = "JournalEntryReversed";

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

        // Linkage semantics (story A3, issue #943): on a reversal,
        // reversalJournalEntry points at the original it reverses; on a
        // reversed original, reversedByJournalEntry points at its reversal.
        JournalEntryResponse originalEntry = toResponseOrNull(entry.getReversalJournalEntryId());

        JournalEntryResponse reversalEntry = toResponseOrNull(entry.getReversedByJournalEntryId());
        if (reversalEntry == null) {
            reversalEntry = journalEntryRepository
                    .findByReversalReference(journalEntryId)
                    .map(JournalEntryMapper::toResponse)
                    .orElse(null);
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
     * Posts a draft journal entry without a period override (system/engine
     * paths). Delegates to {@link #postJournalEntry(UUID, String)}.
     */
    @Override
    public JournalEntry postJournalEntry(UUID journalEntryId) {
        return postJournalEntry(journalEntryId, null);
    }

    /**
     * Posts a draft journal entry to GL (transitions to POSTED).
     * Immutable thereafter; GL account balances updated.
     *
     * <p>Period enforcement (story B2, issue #944): this method is the single
     * choke point for all posting paths — manual post, engine auto-post,
     * credit-memo and payment-application GL posting all funnel through it.
     * The entry's transaction-date month is auto-provisioned OPEN when
     * missing ({@code ensurePeriodExists}; the gate never blocks on a missing
     * row), then {@link AccountingPeriodGate#assertPostingAllowed} applies
     * the hard-lock / closed-period / override rules.
     *
     * @param journalEntryId        entry to post
     * @param overrideJustification optional justification for posting into a
     *                              CLOSED period (requires
     *                              {@code accounting:period:override})
     * @return posted entry
     * @throws IllegalStateException if entry is not in DRAFT status or is
     *                               unbalanced
     */
    @Override
    public JournalEntry postJournalEntry(UUID journalEntryId, @Nullable String overrideJustification) {
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

        // Period gate (B2): provision the month row if absent, then enforce
        // hard-lock / closed-period / override rules on the transaction date.
        LocalDate transactionDate = entry.getTransactionDate().toLocalDate();
        accountingPeriodService.ensurePeriodExists(transactionDate);
        accountingPeriodGate.assertPostingAllowed(transactionDate, journalEntryId, overrideJustification);

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

    // ===== REVERSAL LIFECYCLE (story A3, issue #943) =====

    /**
     * Reverses a posted journal entry by creating an inverse entry and
     * transitioning the original POSTED → REVERSED, all in one transaction.
     *
     * <p>Concurrency: the service-level status check is check-then-act, so
     * the authoritative double-reversal guard is
     * {@link JournalEntryRepository#markReversed} — a conditional
     * {@code UPDATE ... WHERE status = POSTED} whose update count decides the
     * race. The losing transaction sees 0 rows, throws, and rolls back its
     * reversal insert and consumed entry number. (Both racers also serialize
     * on the {@code accounting_sequence} FOR UPDATE row when their reversal
     * dates share a month, which orders them deterministically.)
     *
     * <p>The reversal entry gets its own posted-entry number via the A2
     * {@link #assignEntryNumber} seam and is saved directly as POSTED (no
     * DRAFT → POSTED transition). The resolved reversal date runs through the
     * full B2 period gate (hard lock, closed period, permissioned override).
     */
    @Override
    public JournalEntry reverseJournalEntry(
            @NonNull UUID originalEntryId, @NonNull String reversalReason, @Nullable LocalDate reversalDate) {
        return reverseJournalEntry(originalEntryId, reversalReason, reversalDate, null);
    }

    @Override
    public JournalEntry reverseJournalEntry(
            @NonNull UUID originalEntryId,
            @NonNull String reversalReason,
            @Nullable LocalDate reversalDate,
            @Nullable String overrideJustification) {
        JournalEntry original = findById(originalEntryId);

        if (original.getStatus() != JournalEntryStatus.POSTED) {
            log.warn("Rejecting reversal of journal entry {} in status {}", originalEntryId, original.getStatus());
            throw new JournalEntryNotReversibleException(originalEntryId, original.getStatus());
        }

        LocalDateTime reversalTransactionDate = resolveReversalTransactionDate(original, reversalDate);
        accountingPeriodService.ensurePeriodExists(reversalTransactionDate.toLocalDate());

        Instant now = Instant.now(clock);

        // Create reversal entry with inverted debits/credits
        JournalEntry reversal = new JournalEntry();
        reversal.setJournalEntryId(UUIDv7Generator.generate());
        reversal.setTransactionDate(reversalTransactionDate);
        reversal.setDescription("REVERSAL of " + original.getJournalEntryId() + " - Reason: " + reversalReason);
        reversal.setSourceEventId(original.getSourceEventId());
        reversal.setStatus(JournalEntryStatus.POSTED); // Reversals post immediately
        reversal.setPostedAt(now);
        reversal.setCreatedAt(now);
        reversal.setUpdatedAt(now);
        // Linkage: the reversal points back at the entry it reverses.
        reversal.setReversalJournalEntry(original);

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

        // Period gate (B2): the reversal posts on its resolved date, so the
        // full hard-lock / closed-period / override rules apply to it. An
        // override audit row references the reversal entry being posted.
        accountingPeriodGate.assertPostingAllowed(
                reversalTransactionDate.toLocalDate(), reversal.getJournalEntryId(), overrideJustification);

        // Reversals get their own posted-entry numbers (A2 seam, AC of #942).
        assignEntryNumber(reversal);
        JournalEntry savedReversal = journalEntryRepository.save(reversal);

        // Flip the original POSTED -> REVERSED; linkage: original points at
        // the reversal that reversed it. Update count 0 = lost the race.
        String actor = currentActor();
        int flipped = journalEntryRepository.markReversed(originalEntryId, savedReversal, now, actor);
        if (flipped == 0) {
            log.warn(
                    "Concurrent reversal race lost for journal entry {}; rolling back reversal {}",
                    originalEntryId,
                    savedReversal.getJournalEntryId());
            throw new JournalEntryNotReversibleException(originalEntryId, JournalEntryStatus.REVERSED);
        }

        recordReversalAudit(original, savedReversal, actor, reversalReason);
        publishReversalEvent(original, savedReversal, reversalTransactionDate.toLocalDate(), reversalReason, actor);

        log.info(
                "Reversed journal entry {} with reversal entry {} ({}) dated {}",
                originalEntryId,
                savedReversal.getJournalEntryId(),
                savedReversal.getEntryNumber(),
                reversalTransactionDate);
        return savedReversal;
    }

    /**
     * Resolves the reversal entry's transaction date (simplified Odoo
     * date-picking): an explicit date is used as requested; the default is
     * the original's transaction date if its period is open, else the current
     * date. Enforcement is not done here — the resolved date runs through the
     * full period gate ({@link AccountingPeriodGate#assertPostingAllowed}),
     * which rejects hard-locked/closed dates or honors a permissioned
     * override (story B2).
     */
    private LocalDateTime resolveReversalTransactionDate(JournalEntry original, @Nullable LocalDate requested) {
        if (requested != null) {
            return requested.atStartOfDay();
        }
        LocalDate originalDate = original.getTransactionDate().toLocalDate();
        if (accountingPeriodService.isPeriodOpen(originalDate)) {
            return original.getTransactionDate();
        }
        return LocalDateTime.now(clock);
    }

    /**
     * Publish the reversal domain event through the transactional outbox so
     * downstream read models see the status flip. Runs in the same
     * transaction as the reversal ({@code saveToOutbox} is
     * {@code Propagation.MANDATORY}), mirroring the
     * {@code InvoicePaymentRecorded} caller pattern: simple-name event type,
     * aggregate = the original journal entry whose state changed. A failed
     * (409) reversal rolls the outbox row back with everything else.
     */
    private void publishReversalEvent(
            JournalEntry original, JournalEntry reversal, LocalDate reversalDate, String reason, String actor) {
        JournalEntryReversed event = new JournalEntryReversed(
                original.getJournalEntryId(),
                reversal.getJournalEntryId(),
                original.getEntryNumber(),
                reversal.getEntryNumber(),
                reversalDate,
                reason,
                actor);
        outboxService.saveToOutbox(
                UUIDv7Generator.generate(),
                AGGREGATE_TYPE_JOURNAL_ENTRY,
                original.getJournalEntryId(),
                EVENT_TYPE_JOURNAL_ENTRY_REVERSED,
                event);
    }

    /**
     * Audit-log the reversal (high-risk operation), mirroring the period
     * close/reopen audit pattern: entity = the original entry whose status
     * flipped, old/new value = the status transition, actor from the
     * security context (ADR-0018), justification = the reversal reason plus
     * the reversal entry's identity for traceability.
     */
    private void recordReversalAudit(JournalEntry original, JournalEntry reversal, String actor, String reason) {
        AccountingAuditLog auditLog = new AccountingAuditLog();
        auditLog.setEntityType(AUDIT_ENTITY_TYPE_JOURNAL_ENTRY);
        auditLog.setEntityId(original.getJournalEntryId());
        auditLog.setOperation(AUDIT_OPERATION_REVERSE);
        auditLog.setUserId(actor);
        auditLog.setJustification(reason);
        auditLog.setOldValue(JournalEntryStatus.POSTED.name());
        auditLog.setNewValue(JournalEntryStatus.REVERSED.name() + " by " + reversal.getJournalEntryId());
        auditLogRepository.save(auditLog);
    }

    /**
     * Acting user from the security context (ADR-0018), falling back to
     * SYSTEM for unauthenticated internal flows.
     */
    private static String currentActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
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
