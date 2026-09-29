package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The reconciliation's one view of the ledger (SPEC §3.7, §3.9; story S4, #2303). Every balance counts
 * entries {@code POSTED} or {@code REVERSED} at their own transaction dates (G15); a day's bound is its
 * last microsecond, 23:59:59.999999, the precision Postgres stores.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationLedger {

    /** 23:59:59.999999 — {@code LocalTime.MAX} would round up to the next midnight in a {@code timestamp(6)}. */
    public static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59, 999_999_000);

    /** The lower bound used when a window has no baseline (§3.1: an account with no acknowledged statement). */
    static final LocalDate NO_BASELINE = LocalDate.of(1900, 1, 1);

    private final JournalEntryLineRepository lines;

    /** The last instant of {@code day} at microsecond precision. */
    public static @NonNull LocalDateTime endOfDay(@NonNull LocalDate day) {
        return day.atTime(END_OF_DAY);
    }

    /** The account's balance at the end of {@code day} (§3.7). */
    public @NonNull BigDecimal balanceAsOf(@NonNull UUID glAccountId, @NonNull LocalDate day) {
        BigDecimal balance = lines.getAccountBalanceAsOf(glAccountId, endOfDay(day));
        return balance != null ? balance : BigDecimal.ZERO;
    }

    /** The account's lines of POSTED entries dated in {@code [from, to]}; a null {@code from} has no lower bound. */
    public @NonNull List<LedgerLine> postedLines(
            @NonNull UUID glAccountId, @Nullable LocalDate from, @NonNull LocalDate to) {
        LocalDate lower = from != null ? from : NO_BASELINE;
        if (lower.isAfter(to)) {
            return List.of();
        }
        return lines.findPostedLinesOnAccountBetween(glAccountId, lower.atStartOfDay(), endOfDay(to)).stream()
                .map(LedgerLine::of)
                .toList();
    }

    /** The account's lines of the given entries, whatever their status, keyed by entry id. */
    public @NonNull Map<UUID, List<LedgerLine>> linesOfEntries(
            @NonNull UUID glAccountId, @NonNull Collection<UUID> journalEntryIds) {
        if (journalEntryIds.isEmpty()) {
            return Map.of();
        }
        return lines.findLinesOnAccountForEntries(glAccountId, journalEntryIds).stream()
                .map(LedgerLine::of)
                .collect(Collectors.groupingBy(LedgerLine::journalEntryId));
    }
}
