package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.repository.EntryAccount;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The journal-entry facts close readiness reads from the ledger (SPEC-manual-bank-reconciliation §5.3; story S6,
 * #2305): the DRAFT entries of a period, the status of adjustment entries, and the accounts an entry posted to. The
 * read model reaches the ledger's repositories only through the core's services, as the module walls require.
 */
@Component
@RequiredArgsConstructor
public class LedgerEntries {

    private final JournalEntryRepository entries;
    private final JournalEntryLineRepository lines;

    /** Ids of the DRAFT entries dated in {@code [start, end]} ({@code DRAFT_JOURNAL_ENTRIES}). */
    public @NonNull List<UUID> draftEntryIds(@NonNull LocalDate start, @NonNull LocalDate end) {
        return entries
                .findByStatusAndTransactionDateInRange(
                        JournalEntryStatus.DRAFT,
                        start.atStartOfDay(),
                        end.plusDays(1).atStartOfDay())
                .stream()
                .map(JournalEntry::getJournalEntryId)
                .toList();
    }

    /** The status of each given entry the tenant holds; an unknown id is absent. */
    public @NonNull Map<UUID, JournalEntryStatus> statuses(@NonNull Collection<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        return entries.findAllById(entryIds).stream()
                .collect(Collectors.toMap(JournalEntry::getJournalEntryId, JournalEntry::getStatus));
    }

    /** The distinct accounts each given entry posted a line to, whatever the entry's status. */
    public @NonNull Map<UUID, Set<UUID>> accountsOf(@NonNull Collection<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> byEntry = new LinkedHashMap<>();
        for (EntryAccount row : lines.findEntryAccounts(entryIds)) {
            byEntry.computeIfAbsent(row.journalEntryId(), k -> new LinkedHashSet<>())
                    .add(row.glAccountId());
        }
        return byEntry;
    }
}
