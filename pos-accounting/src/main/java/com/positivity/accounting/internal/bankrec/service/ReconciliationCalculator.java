package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.ReconciliationEquation.Posting;
import com.positivity.accounting.internal.bankrec.service.ReconciliationEquation.Terms;
import com.positivity.accounting.internal.bankrec.service.ReconciliationEquation.Window;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads a reconciliation live (SPEC-manual-bank-reconciliation §3.7; story S4, #2303): the ledger
 * balances (POSTED or REVERSED entries at their own dates, G15), the outstanding items open at the
 * window's end and at the day before its start, the adjustment postings on the account, the window's
 * baseline, and the unexplained items from the baseline on — recomputed on every read and mutation,
 * never from a stale snapshot.
 *
 * <p>Unexplained (E4 inputs, D2):
 *
 * <ul>
 *   <li>bank — rows on the account dated from the baseline to the window end, {@code UNMATCHED} or
 *       {@code POSSIBLE_DUPLICATE}, settled, in no {@code OPEN} bank-side item (a row in a {@code PROPOSED}
 *       match is still {@code UNMATCHED}); rows carried in from earlier windows count;
 *   <li>ledger — lines on the account of POSTED entries dated from the baseline to the window end, in no
 *       {@code ACCEPTED} match, not a gap bridge's cash line, in no {@code OPEN} item — except an aged
 *       {@code OTHER_LEDGER_TIMING} item not reaffirmed in this reconciliation. A reversal pair never
 *       counts: the REVERSED original is not POSTED and the entry reversing it is left out.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ReconciliationCalculator {

    private static final List<BankTransactionStatus> UNEXPLAINED_BANK_STATUSES =
            List.of(BankTransactionStatus.UNMATCHED, BankTransactionStatus.POSSIBLE_DUPLICATE);

    private final ReconciliationLedger ledger;
    private final BankReconciliationOutstandingItemRepository items;
    private final BankReconciliationAdjustmentRepository adjustments;
    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;
    private final BankReconciliationGlMatchRepository glMatches;
    private final BankRecSettings settings;

    /** Reads the reconciliation live. */
    public @NonNull ReconciliationSnapshot compute(@NonNull BankReconciliation reconciliation) {
        UUID account = reconciliation.getGlAccountId();
        LocalDate start = reconciliation.getStatementStartDate();
        LocalDate end = reconciliation.getStatementEndDate();
        LocalDate dayBefore = start.minusDays(1);

        List<BankReconciliationOutstandingItem> accountItems =
                items.findByGlAccountIdAndItemDateLessThanEqual(account, end);
        List<BankReconciliationAdjustment> accountAdjustments = adjustments.findAllOnAccount(account);
        List<Posting> postings = postings(account, accountAdjustments);

        Window window = new Window(
                reconciliation.getStatementId(),
                start,
                end,
                reconciliation.getStatementOpeningBalance(),
                reconciliation.getStatementClosingBalance());
        Terms terms = ReconciliationEquation.compute(
                window,
                accountItems,
                postings,
                ledger.balanceAsOf(account, end),
                ledger.balanceAsOf(account, dayBefore));

        BankStatement baseline = statements
                .findFirstByGlAccountIdAndStatusAndGapAcknowledgementIsNotNullAndStartDateLessThanEqualOrderByStartDateDesc(
                        account, BankStatementStatus.COMMITTED, start)
                .orElse(null);
        LocalDate baselineDate = baseline != null ? baseline.getStartDate() : null;

        List<BankReconciliationAdjustment> bridges = accountAdjustments.stream()
                .filter(a -> a.getBridgesStatementId() != null
                        && a.getBridgesStatementId().equals(reconciliation.getStatementId()))
                .toList();
        Set<UUID> bridgeEntries = accountAdjustments.stream()
                .filter(a -> a.getBridgesStatementId() != null)
                .map(BankReconciliationAdjustment::getJournalEntryId)
                .collect(Collectors.toSet());

        Unexplained unexplained = unexplained(
                account, baselineDate, end, reconciliation.getReconciliationId(), accountItems, bridgeEntries);

        return new ReconciliationSnapshot(
                terms,
                baseline,
                ReconciliationEquation.openAt(accountItems, OutstandingItemSide.LEDGER, end),
                ReconciliationEquation.openAt(accountItems, OutstandingItemSide.BANK, end),
                ReconciliationEquation.openAt(accountItems, OutstandingItemSide.LEDGER, dayBefore),
                ReconciliationEquation.openAt(accountItems, OutstandingItemSide.BANK, dayBefore),
                ReconciliationEquation.latePostings(postings, end),
                ReconciliationEquation.openingPostings(postings, start, reconciliation.getStatementId()),
                unexplained.bank(),
                unexplained.ledger(),
                unexplained.agedAwaitingReaffirmation(),
                bridges);
    }

    /**
     * The unexplained items on an account from {@code baselineDate} to {@code end} (§3.7 {@code
     * countUnexplainedBank} / {@code countUnexplainedLedger}), the one implementation approval (E4) and close
     * readiness ({@code UNEXPLAINED_*}, §5.3; story S6, #2305) share.
     *
     * @param baselineDate the lower bound; null means none (every item on/before {@code end})
     * @param reaffirmedIn the reconciliation whose reaffirmation spares an aged {@code OTHER_LEDGER_TIMING} item
     *     (§3.6); null spares none
     */
    public @NonNull Unexplained unexplained(
            @NonNull UUID account,
            @Nullable LocalDate baselineDate,
            @NonNull LocalDate end,
            @Nullable UUID reaffirmedIn) {
        Set<UUID> bridgeEntries = adjustments.findAllOnAccount(account).stream()
                .filter(a -> a.getBridgesStatementId() != null)
                .map(BankReconciliationAdjustment::getJournalEntryId)
                .collect(Collectors.toSet());
        return unexplained(
                account,
                baselineDate,
                end,
                reaffirmedIn,
                items.findByGlAccountIdAndItemDateLessThanEqual(account, end),
                bridgeEntries);
    }

    /** The §3.7 unexplained items; {@link #countBank()} and {@link #countLedger()} are exact, never toleranced. */
    public record Unexplained(
            @NonNull List<BankTransaction> bank,
            @NonNull List<LedgerLine> ledger,
            @NonNull List<BankReconciliationOutstandingItem> agedAwaitingReaffirmation) {

        public int countBank() {
            return bank.size();
        }

        public @NonNull BigDecimal sumBank() {
            return bank.stream().map(BankTransaction::getSignedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** Unexplained lines plus aged items awaiting reaffirmation (§3.6). */
        public int countLedger() {
            return ledger.size() + agedAwaitingReaffirmation.size();
        }

        public @NonNull BigDecimal sumLedger() {
            BigDecimal lines = ledger.stream().map(LedgerLine::signedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return lines.add(ReconciliationEquation.sumItems(agedAwaitingReaffirmation));
        }
    }

    private Unexplained unexplained(
            UUID account,
            @Nullable LocalDate baselineDate,
            LocalDate end,
            @Nullable UUID reaffirmedIn,
            List<BankReconciliationOutstandingItem> accountItems,
            Set<UUID> bridgeEntries) {
        List<BankReconciliationOutstandingItem> openItems = accountItems.stream()
                .filter(i -> i.getStatus() == OutstandingItemStatus.OPEN)
                .toList();
        List<BankReconciliationOutstandingItem> aged = openItems.stream()
                .filter(i -> isAged(i, end))
                .filter(i -> !Objects.equals(i.getLastReaffirmedInReconciliationId(), reaffirmedIn))
                .filter(i -> baselineDate == null || !i.getItemDate().isBefore(baselineDate))
                .toList();
        return new Unexplained(
                unexplainedBank(account, baselineDate, end, openItems),
                unexplainedLedger(account, baselineDate, end, openItems, bridgeEntries),
                aged);
    }

    /** Stores the live terms on the row (§3.7: persisted on every mutation). */
    public static void apply(@NonNull BankReconciliation reconciliation, @NonNull ReconciliationSnapshot snapshot) {
        Terms t = snapshot.terms();
        reconciliation.setGlEndingBalance(t.glEndingBalance());
        reconciliation.setGlOpeningBalance(t.glOpeningBalance());
        reconciliation.setSumOutstandingLedgerItems(t.sumOutstandingLedgerItems());
        reconciliation.setSumOutstandingBankItems(t.sumOutstandingBankItems());
        reconciliation.setSumLateAdjustments(t.sumLateAdjustments());
        reconciliation.setSumOpeningAdjustments(t.sumOpeningAdjustments());
        reconciliation.setAdjustedBankBalance(t.adjustedBankBalance());
        reconciliation.setAdjustedBookBalance(t.adjustedBookBalance());
        reconciliation.setDifference(t.difference());
        reconciliation.setOpeningDifference(t.openingDifference());
        reconciliation.setBaselineDate(snapshot.baselineDate());
        reconciliation.setCountUnexplainedBank(snapshot.countUnexplainedBank());
        reconciliation.setSumUnexplainedBank(snapshot.sumUnexplainedBank());
        reconciliation.setCountUnexplainedLedger(snapshot.countUnexplainedLedger());
        reconciliation.setSumUnexplainedLedger(snapshot.sumUnexplainedLedger());
    }

    /**
     * Whether an {@code OPEN} {@code OTHER_LEDGER_TIMING} item is aged at this window's end — dated more
     * than {@code aging-warning-days} before it — and not reaffirmed in this reconciliation (§3.6).
     */
    public boolean isAgedAwaitingReaffirmation(
            @NonNull BankReconciliationOutstandingItem item, @NonNull BankReconciliation reconciliation) {
        return item.getStatus() == OutstandingItemStatus.OPEN
                && isAged(item, reconciliation.getStatementEndDate())
                && !Objects.equals(item.getLastReaffirmedInReconciliationId(), reconciliation.getReconciliationId());
    }

    /** Whether an {@code OTHER_LEDGER_TIMING} item is dated more than {@code aging-warning-days} before {@code day}. */
    public boolean isAged(@NonNull BankReconciliationOutstandingItem item, @NonNull LocalDate day) {
        return item.getItemKind() == OutstandingItemKind.OTHER_LEDGER_TIMING
                && item.getItemDate().isBefore(day.minusDays(settings.agingWarningDays()));
    }

    /** Every adjustment posting on the account: the cash line of each adjustment and of each reversal. */
    private List<Posting> postings(UUID account, List<BankReconciliationAdjustment> accountAdjustments) {
        Set<UUID> entryIds = new HashSet<>();
        for (BankReconciliationAdjustment a : accountAdjustments) {
            entryIds.add(a.getJournalEntryId());
            if (a.getReversalJournalEntryId() != null) {
                entryIds.add(a.getReversalJournalEntryId());
            }
        }
        Map<UUID, List<LedgerLine>> linesByEntry = ledger.linesOfEntries(account, entryIds);
        List<Posting> postings = new ArrayList<>();
        for (BankReconciliationAdjustment a : accountAdjustments) {
            addPostings(postings, a, a.getJournalEntryId(), linesByEntry, false);
            if (a.getReversalJournalEntryId() != null) {
                addPostings(postings, a, a.getReversalJournalEntryId(), linesByEntry, true);
            }
        }
        return postings;
    }

    private static void addPostings(
            List<Posting> postings,
            BankReconciliationAdjustment adjustment,
            UUID entryId,
            Map<UUID, List<LedgerLine>> linesByEntry,
            boolean reversal) {
        for (LedgerLine line : linesByEntry.getOrDefault(entryId, List.of())) {
            postings.add(new Posting(
                    adjustment.getAdjustmentId(),
                    adjustment.getReconciliation().getReconciliationId(),
                    entryId,
                    adjustment.getReconciliation().getStatementEndDate(),
                    adjustment.getBridgesStatementId(),
                    line.date(),
                    line.signedAmount(),
                    reversal));
        }
    }

    private List<BankTransaction> unexplainedBank(
            UUID account,
            @Nullable LocalDate baselineDate,
            LocalDate end,
            List<BankReconciliationOutstandingItem> openItems) {
        List<BankTransaction> rows = baselineDate != null
                ? baselineDate.isAfter(end)
                        ? List.of()
                        : transactions.findByGlAccountIdAndTransactionDateBetweenAndStatusIn(
                                account, baselineDate, end, UNEXPLAINED_BANK_STATUSES)
                : transactions.findByGlAccountIdAndTransactionDateLessThanEqualAndStatusIn(
                        account, end, UNEXPLAINED_BANK_STATUSES);
        Set<UUID> inOpenItems = openItems.stream()
                .map(BankReconciliationOutstandingItem::getBankTransactionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return rows.stream()
                .filter(t -> t.getSettlementState() != SettlementState.PENDING)
                .filter(t -> !inOpenItems.contains(t.getBankTransactionId()))
                .sorted(BankRecOrdering.BANK_TRANSACTIONS)
                .toList();
    }

    private List<LedgerLine> unexplainedLedger(
            UUID account,
            @Nullable LocalDate baselineDate,
            LocalDate end,
            List<BankReconciliationOutstandingItem> openItems,
            Set<UUID> bridgeEntries) {
        List<LedgerLine> lines = ledger.postedLines(account, baselineDate, end).stream()
                .filter(l -> !l.reversalEntry())
                .filter(l -> !bridgeEntries.contains(l.journalEntryId()))
                .toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        Set<UUID> explained = new HashSet<>(acceptedLineIds(lines));
        openItems.stream()
                .map(BankReconciliationOutstandingItem::getGlLineId)
                .filter(Objects::nonNull)
                .forEach(explained::add);
        return lines.stream()
                .filter(l -> !explained.contains(l.lineId()))
                .sorted(BankRecOrdering.LEDGER_LINES)
                .toList();
    }

    private Collection<UUID> acceptedLineIds(List<LedgerLine> lines) {
        return glMatches.findActiveLineIdsInState(
                lines.stream().map(LedgerLine::lineId).toList(), MatchState.ACCEPTED);
    }
}
