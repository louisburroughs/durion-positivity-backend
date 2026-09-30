package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.ACCOUNT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.amount;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.item;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.transaction;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.CandidateReason;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.CandidateFinder.ScoredBank;
import com.positivity.accounting.internal.bankrec.service.CandidateFinder.ScoredLine;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link CandidateFinder} (SPEC §4.6, M3; story S4, #2303): the candidate windows clipped at the statement end,
 * the exclusion of matched and outstanding members, and the deterministic ranking — score descending, then date
 * distance, then id.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CandidateFinder (#2303)")
class CandidateFinderTest {

    @Mock
    private ReconciliationLedger ledger;

    @Mock
    private BankReconciliationGlMatchRepository glMatches;

    @Mock
    private BankReconciliationBankMatchRepository bankMatches;

    @Mock
    private BankReconciliationOutstandingItemRepository items;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private ReconciliationEligibility eligibility;

    private CandidateFinder finder;
    private BankReconciliation recon;

    @BeforeEach
    void setUp() {
        finder = new CandidateFinder(
                ledger, glMatches, bankMatches, items, transactions, eligibility, BankRecSettings.defaults(), usd());
        recon = reconciliation();
    }

    /** A ledger line with no description, so only amount and date score. */
    private static LedgerLine ledgerLine(String signedAmount, LocalDate date) {
        return ledgerLine(UUIDv7Generator.generate(), signedAmount, date);
    }

    private static LedgerLine ledgerLine(UUID lineId, String signedAmount, LocalDate date) {
        return new LedgerLine(
                lineId, UUIDv7Generator.generate(), "JE-1", date, amount(signedAmount), null, null, false);
    }

    private static BankReconciliationGlMatch glMember(UUID glLineId) {
        BankReconciliationGlMatch member = new BankReconciliationGlMatch();
        member.setMatchId(UUIDv7Generator.generate());
        member.setGlLineId(glLineId);
        return member;
    }

    private static BankReconciliationOutstandingItem ledgerItem(UUID glLineId, UUID registeredIn) {
        BankReconciliationOutstandingItem open =
                item(OutstandingItemSide.LEDGER, OutstandingItemKind.OUTSTANDING_CHECK, "-25.0000", START);
        open.setGlLineId(glLineId);
        open.setRegisteredInReconciliationId(registeredIn);
        return open;
    }

    private static List<UUID> lineIds(List<ScoredLine> scored) {
        return scored.stream().map(s -> s.line().lineId()).toList();
    }

    private static List<UUID> bankIds(List<ScoredBank> scored) {
        return scored.stream().map(s -> s.bank().getBankTransactionId()).toList();
    }

    @Nested
    @DisplayName("ledgerCandidates")
    class LedgerCandidates {

        @Test
        @DisplayName("clips the window at the statement end, drops matched and this window's OPEN items, and ranks")
        void clippedExcludedAndRanked() {
            LocalDate bankDate = END.minusDays(2);
            BankTransaction bank = transaction("25.0000", bankDate);
            LedgerLine sameDay = ledgerLine("25.0000", bankDate);
            LedgerLine threeDaysOff = ledgerLine("25.0000", bankDate.minusDays(3));
            LedgerLine wrongAmount = ledgerLine("99.0000", bankDate);
            LedgerLine withinTolerance = ledgerLine("25.0100", bankDate);
            LedgerLine matched = ledgerLine("25.0000", bankDate);
            LedgerLine openHere = ledgerLine("25.0000", bankDate);
            LedgerLine openEarlier = ledgerLine("25.0000", bankDate.minusDays(1));
            List<LedgerLine> pool =
                    List.of(wrongAmount, threeDaysOff, matched, openHere, openEarlier, sameDay, withinTolerance);
            List<UUID> poolIds = pool.stream().map(LedgerLine::lineId).toList();
            when(ledger.postedLines(ACCOUNT_ID, bankDate.minusDays(7), END)).thenReturn(pool);
            when(glMatches.findByGlLineIdInAndActiveTrue(poolIds)).thenReturn(List.of(glMember(matched.lineId())));
            when(items.findByGlLineIdInAndStatus(poolIds, OutstandingItemStatus.OPEN))
                    .thenReturn(List.of(
                            ledgerItem(openHere.lineId(), RECON_ID),
                            ledgerItem(openEarlier.lineId(), UUIDv7Generator.generate())));

            List<ScoredLine> result = finder.ledgerCandidates(recon, bank, 7);

            assertThat(lineIds(result))
                    .containsExactly(
                            sameDay.lineId(),
                            openEarlier.lineId(),
                            threeDaysOff.lineId(),
                            withinTolerance.lineId(),
                            wrongAmount.lineId());
            assertThat(result.getFirst().score().points()).isEqualTo(80);
            assertThat(result.getFirst().score().reasons())
                    .containsExactly(CandidateReason.EXACT_AMOUNT, CandidateReason.DATE_IN_WINDOW);
            assertThat(result.get(3).score().reasons()).contains(CandidateReason.WITHIN_TOLERANCE);
            assertThat(result.getLast().score().points()).isEqualTo(20);
        }

        @Test
        @DisplayName("runs the window to the bank date plus the days when that is before the statement end")
        void windowBeforeStatementEnd() {
            BankTransaction bank = transaction("25.0000", START);
            when(ledger.postedLines(ACCOUNT_ID, START.minusDays(3), START.plusDays(3)))
                    .thenReturn(List.of());

            assertThat(finder.ledgerCandidates(recon, bank, 3)).isEmpty();
            verifyNoInteractions(glMatches, items);
        }

        @Test
        @DisplayName("drops a pooled line farther than the effective days")
        void outsideEffectiveDays() {
            BankTransaction bank = transaction("25.0000", START.plusDays(10));
            LedgerLine near = ledgerLine("25.0000", START.plusDays(8));
            LedgerLine far = ledgerLine("25.0000", START.plusDays(7));
            List<UUID> poolIds = List.of(near.lineId(), far.lineId());
            when(ledger.postedLines(ACCOUNT_ID, START.plusDays(8), START.plusDays(12)))
                    .thenReturn(List.of(near, far));
            when(glMatches.findByGlLineIdInAndActiveTrue(poolIds)).thenReturn(List.of());
            when(items.findByGlLineIdInAndStatus(poolIds, OutstandingItemStatus.OPEN))
                    .thenReturn(List.of());

            assertThat(lineIds(finder.ledgerCandidates(recon, bank, 2))).containsExactly(near.lineId());
        }

        @Test
        @DisplayName("breaks score ties by date distance, then by line id, when widened past W")
        void tieBreaks() {
            BankTransaction bank = transaction("25.0000", START.plusDays(10));
            UUID lowId = UUID.fromString("00000000-0000-7000-8000-000000000001");
            UUID highId = UUID.fromString("00000000-0000-7000-8000-000000000002");
            LedgerLine nineDays = ledgerLine("25.0000", START.plusDays(1));
            LedgerLine eightDaysHigh = ledgerLine(highId, "25.0000", START.plusDays(2));
            LedgerLine eightDaysLow = ledgerLine(lowId, "25.0000", START.plusDays(18));
            List<LedgerLine> pool = List.of(nineDays, eightDaysHigh, eightDaysLow);
            List<UUID> poolIds = pool.stream().map(LedgerLine::lineId).toList();
            when(ledger.postedLines(ACCOUNT_ID, START.minusDays(4), START.plusDays(24)))
                    .thenReturn(pool);
            when(glMatches.findByGlLineIdInAndActiveTrue(poolIds)).thenReturn(List.of());
            when(items.findByGlLineIdInAndStatus(poolIds, OutstandingItemStatus.OPEN))
                    .thenReturn(List.of());

            List<ScoredLine> result = finder.ledgerCandidates(recon, bank, 14);

            assertThat(lineIds(result)).containsExactly(lowId, highId, nineDays.lineId());
            assertThat(result).allSatisfy(s -> {
                assertThat(s.score().points()).isEqualTo(60);
                assertThat(s.score().reasons()).contains(CandidateReason.DATE_OUT_OF_WINDOW);
            });
        }
    }

    @Nested
    @DisplayName("bankCandidates")
    class BankCandidates {

        @Test
        @DisplayName("is empty without a query when the whole window lies after the statement end")
        void windowAfterStatementEnd() {
            LedgerLine subject = ledgerLine("25.0000", END.plusDays(10));

            assertThat(finder.bankCandidates(recon, subject, 7)).isEmpty();
            verifyNoInteractions(transactions, bankMatches, eligibility);
        }

        @Test
        @DisplayName("is empty when no UNMATCHED row lies in the window")
        void noRows() {
            LedgerLine subject = ledgerLine("25.0000", START.plusDays(5));
            when(transactions.findByGlAccountIdAndTransactionDateBetweenAndStatusIn(
                            ACCOUNT_ID,
                            START.minusDays(2),
                            START.plusDays(12),
                            List.of(BankTransactionStatus.UNMATCHED)))
                    .thenReturn(List.of());

            assertThat(finder.bankCandidates(recon, subject, 7)).isEmpty();
            verifyNoInteractions(bankMatches, eligibility);
        }

        @Test
        @DisplayName("drops PENDING, matched and outstanding rows, clips at the statement end, and ranks")
        void excludedAndRanked() {
            LocalDate lineDate = END.minusDays(1);
            LedgerLine subject = ledgerLine("25.0000", lineDate);
            UUID lowId = UUID.fromString("00000000-0000-7000-8000-000000000001");
            UUID highId = UUID.fromString("00000000-0000-7000-8000-000000000002");
            BankTransaction exactHigh = transaction("25.0000", lineDate);
            exactHigh.setBankTransactionId(highId);
            BankTransaction exactLow = transaction("25.0000", lineDate);
            exactLow.setBankTransactionId(lowId);
            BankTransaction exactOneDayOff = transaction("25.0000", lineDate.minusDays(1));
            exactOneDayOff.setBankTransactionId(UUID.fromString("00000000-0000-7000-8000-000000000003"));
            BankTransaction exactOneDayAfter = transaction("25.0000", END);
            exactOneDayAfter.setBankTransactionId(UUID.fromString("00000000-0000-7000-8000-000000000004"));
            BankTransaction wrongAmount = transaction("80.0000", lineDate);
            BankTransaction pending = transaction("25.0000", lineDate);
            pending.setSettlementState(SettlementState.PENDING);
            BankTransaction matched = transaction("25.0000", lineDate);
            BankTransaction outstanding = transaction("25.0000", lineDate);
            List<BankTransaction> rows = List.of(
                    wrongAmount, pending, exactOneDayOff, matched, exactHigh, outstanding, exactLow, exactOneDayAfter);
            List<UUID> rowIds =
                    rows.stream().map(BankTransaction::getBankTransactionId).toList();
            when(transactions.findByGlAccountIdAndTransactionDateBetweenAndStatusIn(
                            ACCOUNT_ID, lineDate.minusDays(7), END, List.of(BankTransactionStatus.UNMATCHED)))
                    .thenReturn(rows);
            when(bankMatches.findByBankTransactionIdInAndActiveTrue(rowIds))
                    .thenReturn(List.of(new BankReconciliationBankMatch(
                            UUIDv7Generator.generate(), matched.getBankTransactionId())));
            when(eligibility.openItemBankIds(rowIds)).thenReturn(Set.of(outstanding.getBankTransactionId()));

            List<ScoredBank> result = finder.bankCandidates(recon, subject, 7);

            assertThat(bankIds(result))
                    .containsExactly(
                            lowId,
                            highId,
                            exactOneDayOff.getBankTransactionId(),
                            exactOneDayAfter.getBankTransactionId(),
                            wrongAmount.getBankTransactionId());
            assertThat(result.getFirst().score().points()).isEqualTo(80);
            assertThat(result.get(2).score().points())
                    .isEqualTo(result.get(3).score().points());
            assertThat(result.get(2).score().dateDistance()).isEqualTo(1);
            assertThat(result.getLast().score().reasons()).doesNotContain(CandidateReason.EXACT_AMOUNT);
        }
    }

    @Nested
    @DisplayName("rankedForEach")
    class RankedForEach {

        @Test
        @DisplayName("is empty without reading the ledger for no bank rows")
        void noRows() {
            Map<UUID, List<ScoredLine>> result = finder.rankedForEach(recon, List.of());

            assertThat(result).isEmpty();
            verifyNoInteractions(ledger, glMatches, items);
        }

        @Test
        @DisplayName("reads one pool from W before the earliest row to the statement end and ranks each row within W")
        void onePoolPerCall() {
            BankTransaction late = transaction("40.0000", START.plusDays(20));
            BankTransaction early = transaction("25.0000", START.plusDays(2));
            LedgerLine nearEarly = ledgerLine("25.0000", START.plusDays(3));
            LedgerLine nearLate = ledgerLine("40.0000", START.plusDays(19));
            List<LedgerLine> pool = List.of(nearEarly, nearLate);
            List<UUID> poolIds = pool.stream().map(LedgerLine::lineId).toList();
            when(ledger.postedLines(ACCOUNT_ID, START.minusDays(5), END)).thenReturn(pool);
            when(glMatches.findByGlLineIdInAndActiveTrue(poolIds)).thenReturn(List.of());
            when(items.findByGlLineIdInAndStatus(poolIds, OutstandingItemStatus.OPEN))
                    .thenReturn(List.of());

            Map<UUID, List<ScoredLine>> result = finder.rankedForEach(recon, List.of(late, early));

            assertThat(result.keySet()).containsExactly(late.getBankTransactionId(), early.getBankTransactionId());
            assertThat(lineIds(result.get(late.getBankTransactionId()))).containsExactly(nearLate.lineId());
            assertThat(lineIds(result.get(early.getBankTransactionId()))).containsExactly(nearEarly.lineId());
            verify(ledger, times(1)).postedLines(any(), any(), any());
            verify(glMatches, times(1)).findByGlLineIdInAndActiveTrue(anyList());
        }
    }
}
