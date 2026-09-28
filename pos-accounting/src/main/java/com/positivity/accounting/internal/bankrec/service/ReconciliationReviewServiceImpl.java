package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationAdjustmentResponse;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationLineResponse;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationApiStatus;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCandidatesResponse.Candidate;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReportResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReviewResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReviewResponse.BankRow;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReviewResponse.ClearingAdjustment;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReviewResponse.Diagnostics;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReviewResponse.Equation;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReviewResponse.LedgerRow;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReviewResponse.Posting;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.AdjustmentStatus;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.ReadinessReason;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.CandidateFinder.ScoredBank;
import com.positivity.accounting.internal.bankrec.service.CandidateFinder.ScoredLine;
import com.positivity.accounting.internal.bankrec.service.ReconciliationEquation.Terms;
import com.positivity.accounting.internal.service.AccountingPeriodGate;
import com.positivity.accounting.internal.service.AccountingPeriodService;
import com.positivity.accounting.internal.service.JournalEntryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The review read model and the report (SPEC-manual-bank-reconciliation §4.7, §4.8; story S4, #2303). Both read
 * the reconciliation live through {@link ReconciliationCalculator}; nothing here writes.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReconciliationReviewServiceImpl implements ReconciliationReviewService {

    static final String OPENING_DIFFERENCE = "OPENING_DIFFERENCE";

    private final ReconciliationSupport support;
    private final ReconciliationCalculator calculator;
    private final CandidateFinder finder;
    private final MatchResponses matchResponses;
    private final BankReconciliationRepository reconciliations;
    private final BankReconciliationMatchRepository matches;
    private final BankReconciliationOutstandingItemRepository items;
    private final BankReconciliationAdjustmentRepository adjustments;
    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;
    private final JournalEntryService journalEntryService;
    private final AccountingPeriodGate periodGate;
    private final AccountingPeriodService periodService;
    private final BankRecSettings settings;
    private final FunctionalCurrency currency;

    @Override
    public @NonNull ReconciliationReviewResponse review(@NonNull UUID reconciliationId) {
        BankReconciliation recon = support.require(reconciliationId);
        ReconciliationSnapshot snapshot = calculator.compute(recon);
        BankStatement statement = recon.getStatementId() == null
                ? null
                : statements.findById(recon.getStatementId()).orElse(null);
        LocalDate end = recon.getStatementEndDate();
        List<BankReconciliationAdjustment> own = adjustments.findByReconciliation_ReconciliationId(reconciliationId);
        List<BankReconciliationMatch> ownMatches = matches.findByReconciliationIdOrderByCreatedAtAsc(reconciliationId);

        return ReconciliationReviewResponse.builder()
                .header(header(recon, snapshot, statement))
                .equation(equation(snapshot, end))
                .diagnostics(diagnostics(recon, snapshot, statement))
                .unresolved(unresolved(recon, snapshot, ownMatches))
                .adjustments(own.stream().map(this::withEntryNumber).toList())
                .evidence(ReconciliationReviewResponse.Evidence.builder()
                        .matches(matchResponses.of(ownMatches))
                        .outstandingItems(
                                items.findByGlAccountIdAndItemDateLessThanEqual(recon.getGlAccountId(), end).stream()
                                        .filter(i -> reconciliationId.equals(i.getRegisteredInReconciliationId()))
                                        .sorted(Comparator.comparing(BankReconciliationOutstandingItem::getItemDate))
                                        .map(i -> OutstandingItemResponse.from(i, end))
                                        .toList())
                        .exclusions(exclusions(recon, snapshot.baselineDate()))
                        .adjustmentsToClearing(clearing(own, end))
                        .statement(statement == null ? null : statementSummary(statement))
                        .build())
                .readiness(readiness(recon, snapshot, ownMatches))
                .build();
    }

    @Override
    public @NonNull ReconciliationReportResponse report(@NonNull UUID reconciliationId) {
        BankReconciliation recon = support.require(reconciliationId);
        ReconciliationSnapshot snapshot = calculator.compute(recon);
        BankStatement statement = recon.getStatementId() == null
                ? null
                : statements.findById(recon.getStatementId()).orElse(null);
        LocalDate end = recon.getStatementEndDate();
        List<BankTransaction> lines = recon.getStatementId() == null
                ? List.of()
                : transactions.findByStatementIdOrderBySourceRowNumberAsc(recon.getStatementId());
        List<BankTransaction> matched = lines.stream()
                .filter(t -> t.getStatus() == BankTransactionStatus.MATCHED)
                .toList();
        List<BankTransaction> outstanding = lines.stream()
                .filter(t -> t.getStatus() != BankTransactionStatus.MATCHED)
                .toList();
        List<BankReconciliationAdjustment> own = adjustments.findByReconciliation_ReconciliationId(reconciliationId);
        List<OutstandingItemResponse> openItems = new ArrayList<>();
        snapshot.ledgerItemsOpenAtEnd().forEach(i -> openItems.add(OutstandingItemResponse.from(i, end)));
        snapshot.bankItemsOpenAtEnd().forEach(i -> openItems.add(OutstandingItemResponse.from(i, end)));
        return ReconciliationReportResponse.builder()
                .reconciliationId(reconciliationId)
                .accountCode(recon.getAccountCode())
                .accountName(recon.getAccountName())
                .currency(recon.getCurrency())
                .statementDate(end)
                .glEndingBalance(snapshot.terms().glEndingBalance())
                .statementEndingBalance(recon.getStatementClosingBalance())
                .totalMatched(sumBank(matched))
                .totalAdjustments(own.stream()
                        .map(BankReconciliationAdjustment::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .totalOutstanding(sumBank(outstanding))
                .matchedLineCount(matched.size())
                .outstandingLineCount(outstanding.size())
                .difference(snapshot.terms().difference())
                .adjustments(own.stream().map(this::withEntryNumber).toList())
                .outstandingLines(outstanding.stream()
                        .map(t -> BankReconciliationLineResponse.from(t, null))
                        .toList())
                .equation(equation(snapshot, end))
                .openingTerms(diagnostics(recon, snapshot, statement))
                .outstandingItems(openItems)
                .countUnexplainedBank(snapshot.countUnexplainedBank())
                .sumUnexplainedBank(snapshot.sumUnexplainedBank())
                .countUnexplainedLedger(snapshot.countUnexplainedLedger())
                .sumUnexplainedLedger(snapshot.sumUnexplainedLedger())
                .adjustmentsToClearing(clearing(own, end))
                .build();
    }

    // ---- blocks ----------------------------------------------------------------------------------

    private ReconciliationReviewResponse.Header header(
            BankReconciliation recon, ReconciliationSnapshot snapshot, @Nullable BankStatement statement) {
        BankStatement baseline = snapshot.baselineStatement();
        return ReconciliationReviewResponse.Header.builder()
                .reconciliationId(recon.getReconciliationId())
                .glAccountId(recon.getGlAccountId())
                .accountCode(recon.getAccountCode())
                .accountName(recon.getAccountName())
                .statementId(recon.getStatementId())
                .statementStartDate(recon.getStatementStartDate())
                .statementEndDate(recon.getStatementEndDate())
                .currency(recon.getCurrency())
                .baselineDate(snapshot.baselineDate())
                .baselineSetByThisStatement(
                        baseline != null && baseline.getStatementId().equals(recon.getStatementId()))
                .gapAcknowledgement(baseline != null ? baseline.getGapAcknowledgement() : null)
                .sourceKind(statement != null ? statement.getSourceKind() : null)
                .status(ReconciliationApiStatus.from(recon.getStatus()))
                .preparer(recon.getCreatedBy())
                .approver(recon.getFinalizedBy())
                .accountingPeriodCode(recon.getAccountingPeriodCode())
                .periodState(periodState(recon.getStatementEndDate()))
                .version(recon.getVersion())
                .build();
    }

    private String periodState(LocalDate day) {
        if (periodGate.isHardLocked(day)) {
            return "HARD_LOCKED";
        }
        return periodService.isPeriodOpen(day) ? "OPEN" : "CLOSED";
    }

    private static Equation equation(ReconciliationSnapshot snapshot, LocalDate end) {
        Terms t = snapshot.terms();
        return Equation.builder()
                .statementClosingBalance(t.statementClosingBalance())
                .outstandingLedgerItems(snapshot.ledgerItemsOpenAtEnd().stream()
                        .map(i -> OutstandingItemResponse.from(i, end))
                        .toList())
                .sumOutstandingLedgerItems(t.sumOutstandingLedgerItems())
                .outstandingBankItems(snapshot.bankItemsOpenAtEnd().stream()
                        .map(i -> OutstandingItemResponse.from(i, end))
                        .toList())
                .sumOutstandingBankItems(t.sumOutstandingBankItems())
                .adjustedBankBalance(t.adjustedBankBalance())
                .glEndingBalance(t.glEndingBalance())
                .lateAdjustments(postings(snapshot.latePostings()))
                .sumLateAdjustments(t.sumLateAdjustments())
                .adjustedBookBalance(t.adjustedBookBalance())
                .difference(t.difference())
                .build();
    }

    private Diagnostics diagnostics(
            BankReconciliation recon, ReconciliationSnapshot snapshot, @Nullable BankStatement statement) {
        Terms t = snapshot.terms();
        BankReconciliationAdjustment bridge = snapshot.bridges().stream()
                .filter(a -> a.getStatus() == AdjustmentStatus.POSTED)
                .findFirst()
                .orElse(null);
        boolean flagged = t.openingDifference() != null
                && !ReconciliationEquation.withinTolerance(t.openingDifference(), currency.tolerance());
        return Diagnostics.builder()
                .statementOpeningBalance(t.statementOpeningBalance())
                .openingLedgerItems(t.openingLedgerItems())
                .openingBankItems(t.openingBankItems())
                .glOpeningBalance(t.glOpeningBalance())
                .openingAdjustments(postings(snapshot.openingPostings()))
                .sumOpeningAdjustments(t.sumOpeningAdjustments())
                .openingDifference(t.openingDifference())
                .bridge(bridge == null ? null : withEntryNumber(bridge))
                .flags(flagged ? List.of(OPENING_DIFFERENCE) : List.of())
                .likelyCause(flagged ? likelyCause(recon, statement, bridge) : null)
                .build();
    }

    /** §3.7: an INVALIDATED predecessor (S5 makes it reachable), or an acknowledged gap not yet bridged. */
    private String likelyCause(
            BankReconciliation recon,
            @Nullable BankStatement statement,
            @Nullable BankReconciliationAdjustment bridge) {
        if (statement != null && statement.getGapAcknowledgement() != null && bridge == null) {
            return "GAP_NOT_BRIDGED";
        }
        boolean invalidatedPredecessor = reconciliations
                .findByGlAccount_GlAccountIdAndStatusOrderByStatementStartDateAsc(
                        recon.getGlAccountId(), ReconciliationStatus.INVALIDATED)
                .stream()
                .anyMatch(r -> r.getStatementEndDate().isBefore(recon.getStatementStartDate()));
        return invalidatedPredecessor ? "INVALIDATED_PREDECESSOR" : "UNKNOWN";
    }

    private ReconciliationReviewResponse.Unresolved unresolved(
            BankReconciliation recon, ReconciliationSnapshot snapshot, List<BankReconciliationMatch> ownMatches) {
        LocalDate end = recon.getStatementEndDate();
        List<BankTransaction> unexplained = snapshot.unexplainedBank();
        List<BankTransaction> unmatched = unexplained.stream()
                .filter(t -> t.getStatus() == BankTransactionStatus.UNMATCHED)
                .toList();
        Map<UUID, List<ScoredLine>> ranked = finder.rankedForEach(recon, unmatched);
        List<BankRow> lateArrivals = unexplained.stream()
                .filter(BankTransaction::isArrivedAfterApproval)
                .map(t -> withNearDuplicates(bankRow(t, null), t))
                .toList();
        List<BankRow> bankRows = unmatched.stream()
                .filter(t -> !t.isArrivedAfterApproval())
                .map(t -> bankRow(t, top(ranked.get(t.getBankTransactionId()))))
                .toList();
        List<BankRow> duplicates = unexplained.stream()
                .filter(t -> t.getStatus() == BankTransactionStatus.POSSIBLE_DUPLICATE)
                .map(t -> withNearDuplicates(bankRow(t, null), t))
                .toList();
        int window = settings.matchDateWindowDays();
        List<LedgerRow> ledgerRows = snapshot.unexplainedLedger().stream()
                .map(l -> {
                    List<ScoredBank> candidates = finder.bankCandidates(recon, l, window);
                    return LedgerRow.builder()
                            .glLineId(l.lineId())
                            .journalEntryId(l.journalEntryId())
                            .entryNumber(l.entryNumber())
                            .date(l.date())
                            .signedAmount(l.signedAmount())
                            .description(l.description() != null ? l.description() : l.entryDescription())
                            .topCandidate(candidates.isEmpty() ? null : candidate(candidates.get(0)))
                            .build();
                })
                .toList();
        return ReconciliationReviewResponse.Unresolved.builder()
                .lateArrivals(lateArrivals)
                .unexplainedBank(bankRows)
                .unexplainedLedger(ledgerRows)
                .possibleDuplicates(duplicates)
                .agedItemsAwaitingReaffirmation(snapshot.agedItemsAwaitingReaffirmation().stream()
                        .map(i -> OutstandingItemResponse.from(i, end))
                        .toList())
                .proposedMatches(matchResponses.of(inState(ownMatches, MatchState.PROPOSED)))
                .brokenMatches(matchResponses.of(inState(ownMatches, MatchState.BROKEN)))
                .build();
    }

    private ReconciliationReviewResponse.Readiness readiness(
            BankReconciliation recon, ReconciliationSnapshot snapshot, List<BankReconciliationMatch> ownMatches) {
        Set<ReadinessReason> reasons = EnumSet.noneOf(ReadinessReason.class);
        if (!ReconciliationEquation.withinTolerance(snapshot.terms().difference(), currency.tolerance())) {
            reasons.add(ReadinessReason.NOT_BALANCED);
        }
        if (snapshot.countUnexplainedBank() > 0) {
            reasons.add(ReadinessReason.UNEXPLAINED_BANK);
        }
        if (snapshot.countUnexplainedLedger() > 0) {
            reasons.add(ReadinessReason.UNEXPLAINED_LEDGER);
        }
        boolean open = recon.getStatus() == ReconciliationStatus.IN_PROGRESS;
        return ReconciliationReviewResponse.Readiness.builder()
                .canSubmit(open && reasons.isEmpty())
                .canApprove(open && reasons.isEmpty())
                .reasons(List.copyOf(reasons))
                .proposalsPending(!inState(ownMatches, MatchState.PROPOSED).isEmpty())
                .countUnexplainedBank(snapshot.countUnexplainedBank())
                .sumUnexplainedBank(snapshot.sumUnexplainedBank())
                .countUnexplainedLedger(snapshot.countUnexplainedLedger())
                .sumUnexplainedLedger(snapshot.sumUnexplainedLedger())
                .build();
    }

    // ---- rows ------------------------------------------------------------------------------------

    private List<BankRow> exclusions(BankReconciliation recon, @Nullable LocalDate baselineDate) {
        List<BankTransactionStatus> excluded = List.of(BankTransactionStatus.EXCLUDED);
        List<BankTransaction> rows = baselineDate != null
                ? transactions.findByGlAccountIdAndTransactionDateBetweenAndStatusIn(
                        recon.getGlAccountId(), baselineDate, recon.getStatementEndDate(), excluded)
                : transactions.findByGlAccountIdAndTransactionDateLessThanEqualAndStatusIn(
                        recon.getGlAccountId(), recon.getStatementEndDate(), excluded);
        return rows.stream()
                .sorted(BankRecOrdering.BANK_TRANSACTIONS)
                .map(t -> bankRow(t, null))
                .toList();
    }

    private BankRow withNearDuplicates(BankRow row, BankTransaction subject) {
        int window = settings.duplicateDateWindowDays();
        List<BankTransaction> pool =
                transactions.findByGlAccountIdAndSignedAmountAndTransactionDateBetweenAndStatusNotIn(
                        subject.getGlAccountId(),
                        subject.getSignedAmount(),
                        subject.getTransactionDate().minusDays(window),
                        subject.getTransactionDate().plusDays(window),
                        List.of(BankTransactionStatus.EXCLUDED, BankTransactionStatus.REMOVED_BY_SOURCE));
        row.setNearDuplicates(NearDuplicateRanker.rank(subject, pool, window).stream()
                .map(t -> bankRow(t, null))
                .toList());
        return row;
    }

    private static BankRow bankRow(BankTransaction t, @Nullable Candidate top) {
        return BankRow.builder()
                .bankTransactionId(t.getBankTransactionId())
                .transactionDate(t.getTransactionDate())
                .signedAmount(t.getSignedAmount())
                .description(t.getDescription())
                .reference(t.getReference())
                .checkNumber(t.getCheckNumber())
                .status(t.getStatus())
                .arrivedAfterApproval(t.isArrivedAfterApproval())
                .firstObservedAt(t.getFirstObservedAt())
                .topCandidate(top)
                .build();
    }

    private static @Nullable Candidate top(@Nullable List<ScoredLine> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return null;
        }
        ScoredLine best = ranked.get(0);
        LedgerLine line = best.line();
        return Candidate.builder()
                .glLineId(line.lineId())
                .journalEntryId(line.journalEntryId())
                .entryNumber(line.entryNumber())
                .date(line.date())
                .signedAmount(line.signedAmount())
                .description(line.description() != null ? line.description() : line.entryDescription())
                .score(best.score().points())
                .reasons(best.score().reasons())
                .dateDistance(best.score().dateDistance())
                .build();
    }

    private static Candidate candidate(ScoredBank best) {
        BankTransaction bank = best.bank();
        return Candidate.builder()
                .bankTransactionId(bank.getBankTransactionId())
                .date(bank.getTransactionDate())
                .signedAmount(bank.getSignedAmount())
                .description(bank.getDescription())
                .score(best.score().points())
                .reasons(best.score().reasons())
                .dateDistance(best.score().dateDistance())
                .build();
    }

    private static List<Posting> postings(Collection<ReconciliationEquation.Posting> postings) {
        return postings.stream()
                .sorted(Comparator.comparing(ReconciliationEquation.Posting::date))
                .map(p -> Posting.builder()
                        .adjustmentId(p.adjustmentId())
                        .reconciliationId(p.reconciliationId())
                        .journalEntryId(p.journalEntryId())
                        .date(p.date())
                        .amount(p.amount())
                        .reversal(p.reversal())
                        .build())
                .toList();
    }

    private static List<ClearingAdjustment> clearing(List<BankReconciliationAdjustment> own, LocalDate end) {
        return own.stream()
                .filter(a -> a.getAdjustmentType() == BankAdjustmentType.OTHER)
                .map(a -> {
                    String kind;
                    UUID link;
                    if (a.getSettlesMatchId() != null) {
                        kind = "RESIDUAL_SETTLEMENT";
                        link = a.getSettlesMatchId();
                    } else if (a.getBridgesStatementId() != null) {
                        kind = "GAP_BRIDGE";
                        link = a.getBridgesStatementId();
                    } else if (a.getBankTransactionId() != null) {
                        kind = "BANK_TRANSACTION";
                        link = a.getBankTransactionId();
                    } else {
                        kind = "NONE";
                        link = null;
                    }
                    LocalDate posted = a.getTransactionDate() != null ? a.getTransactionDate() : end;
                    return ClearingAdjustment.builder()
                            .adjustmentId(a.getAdjustmentId())
                            .journalEntryId(a.getJournalEntryId())
                            .amount(a.getAmount())
                            .transactionDate(a.getTransactionDate())
                            .linkKind(kind)
                            .linkId(link)
                            .justification(a.getJustification())
                            .postedBy(a.getCreatedBy())
                            .ageDays(Math.max(0, ChronoUnit.DAYS.between(posted, end)))
                            .status(a.getStatus() != null ? a.getStatus().name() : null)
                            .build();
                })
                .toList();
    }

    private static ReconciliationReviewResponse.StatementSummary statementSummary(BankStatement s) {
        return ReconciliationReviewResponse.StatementSummary.builder()
                .statementId(s.getStatementId())
                .sourceKind(s.getSourceKind())
                .statementRef(s.getStatementRef())
                .startDate(s.getStartDate())
                .endDate(s.getEndDate())
                .openingBalance(s.getOpeningBalance())
                .closingBalance(s.getClosingBalance())
                .gapAcknowledgement(s.getGapAcknowledgement())
                .build();
    }

    private BankReconciliationAdjustmentResponse withEntryNumber(BankReconciliationAdjustment a) {
        BankReconciliationAdjustmentResponse response = BankReconciliationAdjustmentResponse.from(a);
        response.setEntryNumber(
                journalEntryService.getJournalEntry(a.getJournalEntryId()).getEntryNumber());
        return response;
    }

    private static List<BankReconciliationMatch> inState(List<BankReconciliationMatch> all, MatchState state) {
        return all.stream().filter(m -> m.getState() == state).toList();
    }

    private static BigDecimal sumBank(List<BankTransaction> rows) {
        return rows.stream().map(BankTransaction::getSignedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
