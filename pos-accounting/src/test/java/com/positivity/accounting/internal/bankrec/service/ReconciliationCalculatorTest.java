package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.ACCOUNT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.STATEMENT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.adjustment;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.entry;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.item;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.line;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.postedLine;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.statement;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.AdjustmentStatus;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankRecFixtures.FakeLedger;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The live read of a reconciliation (SPEC §3.7, §8.1, §8.2; story S4, #2303, criteria 1, 5, 6, 10, 14): the
 * baseline lower bound, the reversal-pair rule, the bridge exclusion and the aging escalation, against an
 * in-memory ledger that answers the queries exactly as the JPQL does.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationCalculator — live terms and unexplained items (#2303)")
class ReconciliationCalculatorTest {

    @Mock
    private JournalEntryLineRepository lineRepository;

    @Mock
    private BankReconciliationOutstandingItemRepository items;

    @Mock
    private BankReconciliationAdjustmentRepository adjustments;

    @Mock
    private BankStatementRepository statements;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private BankReconciliationGlMatchRepository glMatches;

    private FakeLedger ledger;
    private final List<BankReconciliationOutstandingItem> accountItems = new ArrayList<>();
    private final List<BankReconciliationAdjustment> accountAdjustments = new ArrayList<>();
    private final List<BankTransaction> bankRows = new ArrayList<>();
    private final Set<UUID> acceptedLines = new java.util.HashSet<>();
    private ReconciliationCalculator calculator;
    private BankReconciliation recon;

    @BeforeEach
    void setUp() {
        ledger = new FakeLedger(lineRepository);
        calculator = new ReconciliationCalculator(
                new ReconciliationLedger(lineRepository),
                items,
                adjustments,
                statements,
                transactions,
                glMatches,
                BankRecSettings.defaults());
        recon = reconciliation();
        lenient()
                .when(items.findByGlAccountIdAndItemDateLessThanEqual(eq(ACCOUNT_ID), any()))
                .thenAnswer(inv -> accountItems.stream()
                        .filter(i -> !i.getItemDate().isAfter(inv.getArgument(1)))
                        .toList());
        lenient().when(adjustments.findAllOnAccount(ACCOUNT_ID)).thenReturn(accountAdjustments);
        lenient()
                .when(transactions.findByGlAccountIdAndTransactionDateBetweenAndStatusIn(
                        eq(ACCOUNT_ID), any(), any(), anyCollection()))
                .thenAnswer(inv -> bank(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        lenient()
                .when(transactions.findByGlAccountIdAndTransactionDateLessThanEqualAndStatusIn(
                        eq(ACCOUNT_ID), any(), anyCollection()))
                .thenAnswer(inv -> bank(LocalDate.MIN, inv.getArgument(1), inv.getArgument(2)));
        lenient()
                .when(glMatches.findActiveLineIdsInState(anyCollection(), eq(MatchState.ACCEPTED)))
                .thenAnswer(inv -> {
                    Collection<UUID> ids = inv.getArgument(0);
                    return ids.stream().filter(acceptedLines::contains).toList();
                });
    }

    private List<BankTransaction> bank(LocalDate from, LocalDate to, Collection<BankTransactionStatus> statuses) {
        return bankRows.stream()
                .filter(t -> !t.getTransactionDate().isBefore(from)
                        && !t.getTransactionDate().isAfter(to))
                .filter(t -> statuses.contains(t.getStatus()))
                .toList();
    }

    private void baseline(LocalDate start) {
        when(statements
                        .findFirstByGlAccountIdAndStatusAndGapAcknowledgementIsNotNullAndStartDateLessThanEqualOrderByStartDateDesc(
                                ACCOUNT_ID, BankStatementStatus.COMMITTED, START))
                .thenReturn(Optional.of(statement(STATEMENT_ID, start, END, "First statement on this account")));
    }

    @Test
    @DisplayName(
            "glEndingBalance is the balance at 23:59:59.999999 of the end date: 23:30 in, next day out (criterion 1)")
    void glEndingBalanceBoundary() {
        ledger.add(line(entryAt(END.atTime(23, 30)), "100"));
        ledger.add(line(entryAt(END.plusDays(1).atStartOfDay()), "40"));

        ReconciliationSnapshot snapshot = calculator.compute(recon);

        assertThat(snapshot.terms().glEndingBalance()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("500 cash lines before the baseline never count; the window starts at the baseline (criterion 6) [M]")
    void baselineBoundsTheLedgerCount() {
        baseline(START);
        for (int i = 0; i < 500; i++) {
            ledger.add(postedLine("10", LocalDate.of(2025, 1, 1).plusDays(i % 300)));
        }
        ledger.add(postedLine("25", LocalDate.of(2026, 9, 12)));

        ReconciliationSnapshot snapshot = calculator.compute(recon);

        assertThat(snapshot.baselineDate()).isEqualTo(START);
        assertThat(snapshot.countUnexplainedLedger()).isEqualTo(1);
        assertThat(snapshot.sumUnexplainedLedger()).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("without an acknowledged statement there is no lower bound")
    void noBaselineMeansNoLowerBound() {
        ledger.add(postedLine("10", LocalDate.of(2025, 1, 1)));
        ReconciliationSnapshot snapshot = calculator.compute(recon);
        assertThat(snapshot.baselineDate()).isNull();
        assertThat(snapshot.countUnexplainedLedger()).isEqualTo(1);
    }

    @Test
    @DisplayName("a reversal pair never counts; the matched fee's bank row returns and counts (criterion 5) [M]")
    void reversalPairsNeverCount() {
        baseline(START);
        // A fee adjustment's cash line (−15), reversed: the original is REVERSED, the reversal POSTED.
        JournalEntry fee = entry(LocalDate.of(2026, 9, 10), JournalEntryStatus.REVERSED);
        ledger.add(line(fee, "-15"));
        JournalEntry reversal = entry(LocalDate.of(2026, 9, 20), JournalEntryStatus.POSTED);
        reversal.setReversalJournalEntry(fee);
        ledger.add(line(reversal, "15"));
        BankTransaction bankFee = transaction("-15", LocalDate.of(2026, 9, 10));
        bankRows.add(bankFee);

        ReconciliationSnapshot snapshot = calculator.compute(recon);

        assertThat(snapshot.countUnexplainedLedger()).isZero();
        assertThat(snapshot.unexplainedBank()).containsExactly(bankFee);
        assertThat(snapshot.terms().glEndingBalance())
                .as("the pair nets to zero")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a gap bridge's cash line is never unexplained (§8.2) [M]")
    void bridgeLineIsExplained() {
        baseline(START);
        JournalEntry bridgeEntry = entry(LocalDate.of(2026, 9, 1), JournalEntryStatus.POSTED);
        ledger.add(line(bridgeEntry, "-45.67"));
        BankReconciliationAdjustment bridge =
                adjustment(recon, BankAdjustmentType.OTHER, "-45.67", bridgeEntry.getJournalEntryId());
        bridge.setBridgesStatementId(STATEMENT_ID);
        accountAdjustments.add(bridge);

        ReconciliationSnapshot snapshot = calculator.compute(recon);

        assertThat(snapshot.countUnexplainedLedger()).isZero();
        assertThat(snapshot.bridges()).containsExactly(bridge);
        assertThat(snapshot.terms().sumOpeningAdjustments())
                .as("dated on the window start, the bridge enters the opening terms")
                .isEqualByComparingTo("-45.67");
    }

    @Test
    @DisplayName("an ACCEPTED match explains a line; a PROPOSED one does not; an OPEN item does")
    void acceptedMatchesAndItemsExplain() {
        baseline(START);
        JournalEntryLine accepted = ledger.add(postedLine("100", LocalDate.of(2026, 9, 5)));
        JournalEntryLine proposed = ledger.add(postedLine("200", LocalDate.of(2026, 9, 6)));
        JournalEntryLine outstanding = ledger.add(postedLine("300", LocalDate.of(2026, 9, 29)));
        acceptedLines.add(accepted.getLineId());
        BankReconciliationOutstandingItem deposit = item(
                OutstandingItemSide.LEDGER, OutstandingItemKind.DEPOSIT_IN_TRANSIT, "300", LocalDate.of(2026, 9, 29));
        deposit.setGlLineId(outstanding.getLineId());
        accountItems.add(deposit);

        ReconciliationSnapshot snapshot = calculator.compute(recon);

        assertThat(snapshot.unexplainedLedger()).extracting(LedgerLine::lineId).containsExactly(proposed.getLineId());
        assertThat(snapshot.terms().sumOutstandingLedgerItems()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("a deposit in transit left unregistered is one unexplained ledger line (criterion 10)")
    void unregisteredDepositIsUnexplained() {
        baseline(START);
        ledger.add(postedLine("500", LocalDate.of(2026, 9, 30)));
        assertThat(calculator.compute(recon).countUnexplainedLedger()).isEqualTo(1);
    }

    @Test
    @DisplayName("an aged OTHER_LEDGER_TIMING item counts until reaffirmed in this reconciliation (criterion 14) [M]")
    void agedTimingItemCountsUntilReaffirmedHere() {
        baseline(LocalDate.of(2026, 1, 1));
        JournalEntryLine old = ledger.add(postedLine("80", LocalDate.of(2026, 5, 1)));
        BankReconciliationOutstandingItem timing = item(
                OutstandingItemSide.LEDGER, OutstandingItemKind.OTHER_LEDGER_TIMING, "80", LocalDate.of(2026, 5, 1));
        timing.setGlLineId(old.getLineId());
        accountItems.add(timing);

        assertThat(calculator.compute(recon).countUnexplainedLedger()).isEqualTo(1);

        timing.setLastReaffirmedInReconciliationId(UUID.randomUUID());
        assertThat(calculator.compute(recon).countUnexplainedLedger())
                .as("a reaffirmation in another (earlier) window does not carry over")
                .isEqualTo(1);

        timing.setLastReaffirmedInReconciliationId(RECON_ID);
        assertThat(calculator.compute(recon).countUnexplainedLedger()).isZero();
    }

    @Test
    @DisplayName("a timing item within the aging days is explained")
    void freshTimingItemIsExplained() {
        baseline(START);
        JournalEntryLine fresh = ledger.add(postedLine("80", LocalDate.of(2026, 9, 20)));
        BankReconciliationOutstandingItem timing = item(
                OutstandingItemSide.LEDGER, OutstandingItemKind.OTHER_LEDGER_TIMING, "80", LocalDate.of(2026, 9, 20));
        timing.setGlLineId(fresh.getLineId());
        accountItems.add(timing);
        assertThat(calculator.compute(recon).countUnexplainedLedger()).isZero();
    }

    @Test
    @DisplayName("bank: UNMATCHED and POSSIBLE_DUPLICATE rows count; a row in an OPEN item or PENDING does not")
    void unexplainedBankRows() {
        baseline(START);
        BankTransaction unmatched = transaction("40", LocalDate.of(2026, 9, 3));
        BankTransaction duplicate = transaction("40", LocalDate.of(2026, 9, 3));
        duplicate.setStatus(BankTransactionStatus.POSSIBLE_DUPLICATE);
        BankTransaction pending = transaction("12", LocalDate.of(2026, 9, 4));
        pending.setSettlementState(SettlementState.PENDING);
        BankTransaction bankError = transaction("75", LocalDate.of(2026, 9, 5));
        BankTransaction carriedIn = transaction("9", LocalDate.of(2026, 8, 30));
        bankRows.addAll(List.of(unmatched, duplicate, pending, bankError, carriedIn));
        BankReconciliationOutstandingItem error =
                item(OutstandingItemSide.BANK, OutstandingItemKind.BANK_ERROR_PENDING, "75", LocalDate.of(2026, 9, 5));
        error.setBankTransactionId(bankError.getBankTransactionId());
        accountItems.add(error);

        ReconciliationSnapshot snapshot = calculator.compute(recon);

        assertThat(snapshot.unexplainedBank()).containsExactlyInAnyOrder(unmatched, duplicate);
        assertThat(snapshot.sumUnexplainedBank()).isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("the shared E4/readiness predicate counts POSTED bank rows only; PENDING never counts [M]")
    void sharedPredicateCountsPostedOnly() {
        BankTransaction posted = transaction("40", LocalDate.of(2026, 9, 3));
        BankTransaction pending = transaction("12", LocalDate.of(2026, 9, 4));
        pending.setSettlementState(SettlementState.PENDING);
        BankTransaction pendingDuplicate = transaction("7", LocalDate.of(2026, 9, 5));
        pendingDuplicate.setStatus(BankTransactionStatus.POSSIBLE_DUPLICATE);
        pendingDuplicate.setSettlementState(SettlementState.PENDING);
        bankRows.addAll(List.of(posted, pending, pendingDuplicate));

        ReconciliationCalculator.Unexplained withBaseline = calculator.unexplained(ACCOUNT_ID, START, END, null);
        ReconciliationCalculator.Unexplained withoutBaseline = calculator.unexplained(ACCOUNT_ID, null, END, null);

        assertThat(withBaseline.bank()).containsExactly(posted);
        assertThat(withBaseline.sumBank()).isEqualByComparingTo("40");
        assertThat(withoutBaseline.bank()).containsExactly(posted);
    }

    @Test
    @DisplayName(
            "glEndingBalance is read at END 23:59:59.999999, the bound close readiness re-reads (BALANCE_AGREEMENT)")
    void glEndingBalanceUsesTheSharedEndOfDay() {
        calculator.compute(recon);

        ArgumentCaptor<LocalDateTime> asOf = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(lineRepository, atLeastOnce()).getAccountBalanceAsOf(eq(ACCOUNT_ID), asOf.capture());
        assertThat(asOf.getAllValues())
                .contains(END.atTime(23, 59, 59, 999_999_000))
                .contains(START.minusDays(1).atTime(23, 59, 59, 999_999_000));
        assertThat(ReconciliationLedger.endOfDay(END)).isEqualTo(END.atTime(23, 59, 59, 999_999_000));
    }

    @Test
    @DisplayName("apply stores every computed term on the row")
    void applyStoresTheTerms() {
        baseline(START);
        ledger.add(postedLine("100", LocalDate.of(2026, 8, 15)));
        ledger.add(postedLine("50", LocalDate.of(2026, 9, 15)));
        recon.setStatementOpeningBalance(new java.math.BigDecimal("100"));
        recon.setStatementClosingBalance(new java.math.BigDecimal("150"));
        BankReconciliationAdjustment reversed = adjustment(recon, BankAdjustmentType.BANK_FEE, "-5", UUID.randomUUID());
        reversed.setStatus(AdjustmentStatus.REVERSED);

        ReconciliationSnapshot snapshot = calculator.compute(recon);
        ReconciliationCalculator.apply(recon, snapshot);

        assertThat(recon.getGlEndingBalance()).isEqualByComparingTo("150");
        assertThat(recon.getGlOpeningBalance()).isEqualByComparingTo("100");
        assertThat(recon.getDifference()).isEqualByComparingTo("0");
        assertThat(recon.getOpeningDifference()).isEqualByComparingTo("0");
        assertThat(recon.getBaselineDate()).isEqualTo(START);
        assertThat(recon.getCountUnexplainedLedger()).isEqualTo(1);
        assertThat(recon.getCountUnexplainedBank()).isZero();
        assertThat(recon.getAdjustedBankBalance()).isEqualByComparingTo("150");
        assertThat(recon.getAdjustedBookBalance()).isEqualByComparingTo("150");
    }

    private static JournalEntry entryAt(java.time.LocalDateTime at) {
        JournalEntry entry = entry(at.toLocalDate(), JournalEntryStatus.POSTED);
        entry.setTransactionDate(at);
        return entry;
    }
}
