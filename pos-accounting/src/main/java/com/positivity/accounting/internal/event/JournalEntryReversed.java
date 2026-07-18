package com.positivity.accounting.internal.event;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Domain event published (via the transactional outbox) when a posted journal
 * entry is reversed: the original flips POSTED → REVERSED and a numbered
 * reversal entry is created. Downstream read models consume this to see the
 * status flip and the reversal linkage (story A3, issue #943).
 *
 * @param originalJournalEntryId the reversed (original) entry
 * @param reversalJournalEntryId the newly created reversal entry
 * @param originalEntryNumber    posted-entry number of the original (null for
 *                               entries posted before numbering existed)
 * @param reversalEntryNumber    posted-entry number of the reversal
 * @param reversalDate           resolved transaction date of the reversal
 * @param reason                 caller-supplied reversal reason
 * @param actor                  acting user (SYSTEM for internal flows)
 */
public record JournalEntryReversed(
        UUID originalJournalEntryId,
        UUID reversalJournalEntryId,
        String originalEntryNumber,
        String reversalEntryNumber,
        LocalDate reversalDate,
        String reason,
        String actor) {}
