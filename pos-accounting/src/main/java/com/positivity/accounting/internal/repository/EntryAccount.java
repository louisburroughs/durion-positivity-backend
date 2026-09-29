package com.positivity.accounting.internal.repository;

import java.util.UUID;

/**
 * One account a journal entry posted a line to (bank reconciliation close readiness reads the clearing accounts
 * from the {@code OTHER} adjustments' entries, SPEC-manual-bank-reconciliation §5.3; story S6, #2305).
 */
public record EntryAccount(UUID journalEntryId, UUID glAccountId) {}
