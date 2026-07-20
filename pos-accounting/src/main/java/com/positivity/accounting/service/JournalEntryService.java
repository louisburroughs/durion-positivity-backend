package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.JournalEntryTraceabilityResponse;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface JournalEntryService {

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
    JournalEntry createJournalEntry(JournalEntry entry);

    /**
     * Retrieves an existing journal entry by ID.
     *
     * <p>
     * <strong>Note:</strong> Internal callers within this class must use
     * {@link #findById(UUID)} instead, because self-invocation bypasses the
     * Spring proxy and the {@code readOnly} hint would not be applied.
     * </p>
     */
    JournalEntry getJournalEntry(UUID journalEntryId);

    /**
     * Retrieves traceability details for a journal entry, including related entries
     * linked by source event and reversal relationships when available.
     *
     * @param journalEntryId journal entry identifier
     * @return traceability response
     */
    JournalEntryTraceabilityResponse getJournalTraceability(UUID journalEntryId);

    /**
     * Updates a draft journal entry (DRAFT status only).
     * Once POSTED, entries are immutable; use reverse() instead.
     *
     * @param journalEntryId entry to update
     * @param updates        journal entry with new values
     * @return updated entry
     * @throws IllegalStateException if entry is not in DRAFT status
     */
    JournalEntry updateJournalEntry(UUID journalEntryId, JournalEntry updates);

    /**
     * Posts a draft journal entry to GL without a period override —
     * convenience for {@link #postJournalEntry(UUID, String)} with a null
     * justification (system/engine posting paths never override).
     *
     * @param journalEntryId entry to post
     * @return posted entry
     * @throws IllegalStateException if entry is not in DRAFT status or is
     *                               unbalanced
     */
    JournalEntry postJournalEntry(UUID journalEntryId);

    /**
     * Posts a draft journal entry to GL (transitions to POSTED).
     * Immutable thereafter; GL account balances updated.
     *
     * <p>Period enforcement (story B2, issue #944): the entry's transaction
     * date is checked by the accounting-period gate. A date before the
     * org-level hard-lock date is rejected unconditionally (422:
     * PERIOD_HARD_LOCKED). A date in a CLOSED period is rejected (422:
     * PERIOD_CLOSED) unless the caller holds
     * {@code accounting:period:override} and supplies a non-blank
     * {@code overrideJustification}, in which case the posting proceeds and
     * the override is audit-logged. A missing period row counts as OPEN and
     * is auto-provisioned.
     *
     * @param journalEntryId        entry to post
     * @param overrideJustification optional justification for posting into a
     *                              CLOSED period; requires the
     *                              {@code accounting:period:override}
     *                              permission
     * @return posted entry
     * @throws IllegalStateException if entry is not in DRAFT status or is
     *                               unbalanced
     * @throws com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException
     *         if the transaction date is before the hard-lock date (422:
     *         PERIOD_HARD_LOCKED, no override)
     * @throws com.positivity.accounting.internal.exception.AccountingPeriodClosedException
     *         if the transaction date's period is CLOSED and no valid
     *         override applies (422: PERIOD_CLOSED)
     */
    JournalEntry postJournalEntry(@NonNull UUID journalEntryId, @Nullable String overrideJustification);

    /**
     * Reverses a posted journal entry by creating an inverse entry (story A3,
     * issue #943).
     *
     * <p>Lifecycle effects, all in one transaction:
     * <ul>
     * <li>the reversal entry is created with inverted lines, its own posted
     * entry number, and saved immediately POSTED (no DRAFT → POSTED
     * transition); its {@code reversalJournalEntry} link points at the
     * original</li>
     * <li>the original transitions POSTED → REVERSED with {@code reversedAt}
     * stamped and its {@code reversedByJournalEntry} link pointing at the
     * reversal; the flip is guarded by a conditional UPDATE so a concurrent
     * double reversal loses the race and aborts</li>
     * <li>an {@link com.positivity.accounting.internal.entity.AccountingAuditLog}
     * row records the reversal with the acting user</li>
     * </ul>
     *
     * <p>Reversal date: an explicit {@code reversalDate} must fall in an OPEN
     * accounting period. When null, the original's transaction date is used
     * if its period is open, otherwise the current date (whose period must be
     * open) — Odoo's date-picking behavior, simplified.
     *
     * @param originalEntryId entry to reverse
     * @param reversalReason  reason for reversal (e.g., "CORRECTION", "ADJUSTMENT")
     * @param reversalDate    optional transaction date for the reversal entry;
     *                        null selects the default described above
     * @return reversal journal entry (POSTED, numbered)
     * @throws IllegalArgumentException if the original entry is not found
     * @throws com.positivity.accounting.internal.exception.JournalEntryNotReversibleException
     *         if the original is not POSTED (409: JE_ALREADY_REVERSED for
     *         REVERSED — including a lost concurrent-reversal race — or
     *         JE_NOT_POSTED for DRAFT/PENDING)
     * @throws com.positivity.accounting.internal.exception.AccountingPeriodClosedException
     *         if the resolved reversal date falls in a CLOSED period (422:
     *         PERIOD_CLOSED)
     */
    JournalEntry reverseJournalEntry(
            @NonNull UUID originalEntryId, @NonNull String reversalReason, @Nullable LocalDate reversalDate);

    /**
     * Reverses a posted journal entry with an optional period override
     * (story B2, issue #944). Behaves like
     * {@link #reverseJournalEntry(UUID, String, LocalDate)}, but the resolved
     * reversal date goes through the full accounting-period gate: a date
     * before the hard-lock date is rejected unconditionally (422:
     * PERIOD_HARD_LOCKED); a date in a CLOSED period is rejected (422:
     * PERIOD_CLOSED) unless the caller holds
     * {@code accounting:period:override} and supplies a non-blank
     * {@code overrideJustification}, in which case the reversal posts into
     * the closed period and the override is audit-logged.
     *
     * @param originalEntryId       entry to reverse
     * @param reversalReason        reason for reversal
     * @param reversalDate          optional transaction date for the reversal
     *                              entry; null selects the A3 default
     *                              (original's date if its period is open,
     *                              else the current date)
     * @param overrideJustification optional justification for reversing into
     *                              a CLOSED period; requires the
     *                              {@code accounting:period:override}
     *                              permission
     * @return reversal journal entry (POSTED, numbered)
     * @throws com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException
     *         if the resolved reversal date is before the hard-lock date
     *         (422: PERIOD_HARD_LOCKED, no override)
     */
    JournalEntry reverseJournalEntry(
            @NonNull UUID originalEntryId,
            @NonNull String reversalReason,
            @Nullable LocalDate reversalDate,
            @Nullable String overrideJustification);

    /**
     * Find the original (non-reversal) journal entry posted for a source event
     * id, if one exists.
     *
     * <p>Used by the AR payment-application reversal GL poster (story C2, issue
     * #958) to locate the C1 cash-receipt entry to reverse by its posting key.
     * A reversal entry shares the original's {@code sourceEventId} but is
     * excluded here: only the entry whose {@code reversalJournalEntry} link is
     * null (i.e. it does not itself reverse anything) is returned. At most one
     * such entry can exist because posting is idempotent per source event.
     *
     * @param sourceEventId source event id (the posting key) to look up
     * @return the original entry for the source event, or empty if none is
     *         posted yet
     */
    Optional<JournalEntry> findOriginalBySourceEvent(@NonNull UUID sourceEventId);

    /**
     * Lists journal entries with pagination and optional filtering.
     *
     * @param pageable    page, size, and sort
     * @param entryNumber optional exact-match filter on the posted-entry number
     *                    ({@code JE-&#123;YYYYMM&#125;-&#123;seq&#125;}); null or blank
     *                    means no filter. Drafts, PENDING entries, and entries
     *                    posted before numbering existed have no entry number and
     *                    never match.
     * @return page of matching journal entries
     */
    Page<JournalEntry> listJournalEntries(@NonNull Pageable pageable, @Nullable String entryNumber);

    /**
     * Find all posted entries for audit or reconciliation.
     */
    Page<JournalEntry> listPostedEntries(Pageable pageable);

    /**
     * Find entries by status (DRAFT, POSTED, REVERSED).
     */
    List<JournalEntry> findByStatus(JournalEntryStatus status);
}
