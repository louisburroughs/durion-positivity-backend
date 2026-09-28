package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.CandidateScorer.Score;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Finds and ranks match candidates (SPEC §4.6; story S4, #2303), deterministic: score descending, then date
 * distance, then id. A ledger candidate is a POSTED line on the account dated within the window and never after
 * the window end (M3), in no active match and in no {@code OPEN} item this reconciliation registered (an earlier
 * window's item is exactly what a match here clears). A bank candidate is an {@code UNMATCHED}, settled row on
 * the account dated within the window and on or before its end, in no active match and no {@code OPEN} item.
 */
@Component
@RequiredArgsConstructor
public class CandidateFinder {

    private final ReconciliationLedger ledger;
    private final BankReconciliationGlMatchRepository glMatches;
    private final BankReconciliationBankMatchRepository bankMatches;
    private final BankReconciliationOutstandingItemRepository items;
    private final BankTransactionRepository transactions;
    private final ReconciliationEligibility eligibility;
    private final BankRecSettings settings;
    private final FunctionalCurrency currency;

    /** A ledger line and its score against a bank transaction. */
    public record ScoredLine(
            @NonNull LedgerLine line, @NonNull Score score) {}

    /** A bank transaction and its score against a ledger line. */
    public record ScoredBank(
            @NonNull BankTransaction bank, @NonNull Score score) {}

    /** Ledger candidates of a bank transaction, within {@code effectiveDays} (≥ W when widened). */
    public @NonNull List<ScoredLine> ledgerCandidates(
            @NonNull BankReconciliation recon, @NonNull BankTransaction bank, int effectiveDays) {
        LocalDate from = bank.getTransactionDate().minusDays(effectiveDays);
        LocalDate to = min(bank.getTransactionDate().plusDays(effectiveDays), recon.getStatementEndDate());
        return rank(bank, eligibleLedger(recon, from, to), effectiveDays);
    }

    /** Bank candidates of a ledger line, within {@code effectiveDays}. */
    public @NonNull List<ScoredBank> bankCandidates(
            @NonNull BankReconciliation recon, @NonNull LedgerLine subject, int effectiveDays) {
        LocalDate from = subject.date().minusDays(effectiveDays);
        LocalDate to = min(subject.date().plusDays(effectiveDays), recon.getStatementEndDate());
        if (from.isAfter(to)) {
            return List.of();
        }
        List<BankTransaction> rows = transactions.findByGlAccountIdAndTransactionDateBetweenAndStatusIn(
                recon.getGlAccountId(), from, to, List.of(BankTransactionStatus.UNMATCHED));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids =
                rows.stream().map(BankTransaction::getBankTransactionId).toList();
        Set<UUID> excluded = bankMatches.findByBankTransactionIdInAndActiveTrue(ids).stream()
                .map(BankReconciliationBankMatch::getBankTransactionId)
                .collect(Collectors.toCollection(HashSet::new));
        excluded.addAll(eligibility.openItemBankIds(ids));
        int window = settings.matchDateWindowDays();
        return rows.stream()
                .filter(t -> t.getSettlementState() != SettlementState.PENDING)
                .filter(t -> !excluded.contains(t.getBankTransactionId()))
                .map(t -> new ScoredBank(t, CandidateScorer.score(t, subject, window, currency.tolerance())))
                .sorted(Comparator.comparing((ScoredBank s) -> -s.score().points())
                        .thenComparingLong(s -> s.score().dateDistance())
                        .thenComparing(s -> s.bank().getBankTransactionId()))
                .toList();
    }

    /**
     * The ranked ledger candidates of each bank row within W, from one pool read once (auto-match and the
     * review), keyed by bank transaction id in the given order.
     */
    public @NonNull Map<UUID, List<ScoredLine>> rankedForEach(
            @NonNull BankReconciliation recon, @NonNull Collection<BankTransaction> bankRows) {
        Map<UUID, List<ScoredLine>> ranked = new LinkedHashMap<>();
        if (bankRows.isEmpty()) {
            return ranked;
        }
        int window = settings.matchDateWindowDays();
        LocalDate from = bankRows.stream()
                .map(BankTransaction::getTransactionDate)
                .min(Comparator.naturalOrder())
                .orElseThrow()
                .minusDays(window);
        List<LedgerLine> pool = eligibleLedger(recon, from, recon.getStatementEndDate());
        for (BankTransaction bank : bankRows) {
            ranked.put(bank.getBankTransactionId(), rank(bank, pool, window));
        }
        return ranked;
    }

    private List<ScoredLine> rank(BankTransaction bank, List<LedgerLine> pool, int effectiveDays) {
        int window = settings.matchDateWindowDays();
        return pool.stream()
                .filter(l -> Math.abs(ChronoUnit.DAYS.between(bank.getTransactionDate(), l.date())) <= effectiveDays)
                .map(l -> new ScoredLine(l, CandidateScorer.score(bank, l, window, currency.tolerance())))
                .sorted(Comparator.comparing((ScoredLine s) -> -s.score().points())
                        .thenComparingLong(s -> s.score().dateDistance())
                        .thenComparing(s -> s.line().lineId()))
                .toList();
    }

    private List<LedgerLine> eligibleLedger(BankReconciliation recon, LocalDate from, LocalDate to) {
        List<LedgerLine> candidates = ledger.postedLines(recon.getGlAccountId(), from, to);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = candidates.stream().map(LedgerLine::lineId).toList();
        Set<UUID> excluded = glMatches.findByGlLineIdInAndActiveTrue(ids).stream()
                .map(BankReconciliationGlMatch::getGlLineId)
                .collect(Collectors.toCollection(HashSet::new));
        items.findByGlLineIdInAndStatus(ids, OutstandingItemStatus.OPEN).stream()
                .filter(i -> recon.getReconciliationId().equals(i.getRegisteredInReconciliationId()))
                .map(BankReconciliationOutstandingItem::getGlLineId)
                .forEach(excluded::add);
        return candidates.stream().filter(l -> !excluded.contains(l.lineId())).toList();
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
