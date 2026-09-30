package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.amount;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.item;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchOrigin;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.MatchWriter.Header;
import com.positivity.accounting.internal.bankrec.service.MatchWriter.LedgerMember;
import com.positivity.accounting.internal.exception.ReconciliationLineIneligibleException;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link MatchWriter} (SPEC §3.4, §5.4; story S4, #2303): the match header and members it writes, the bank-row
 * status and ledger-side item clearing an {@code ACCEPTED} match carries, the U4 race mapped to 409, and the
 * unmatch / reject / break paths that end a match.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchWriter (#2303)")
class MatchWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-02T15:00:00Z");
    private static final String ACTOR = "preparer";

    @Mock
    private BankReconciliationMatchRepository matches;

    @Mock
    private BankReconciliationGlMatchRepository glMatches;

    @Mock
    private BankReconciliationBankMatchRepository bankMatches;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private BankReconciliationOutstandingItemRepository items;

    @Captor
    private ArgumentCaptor<BankReconciliationMatch> savedMatch;

    @Captor
    private ArgumentCaptor<List<BankReconciliationBankMatch>> savedBankMembers;

    @Captor
    private ArgumentCaptor<List<BankReconciliationGlMatch>> savedGlMembers;

    @Captor
    private ArgumentCaptor<List<BankReconciliationOutstandingItem>> savedItems;

    private MatchWriter writer;
    private BankReconciliation recon;

    @BeforeEach
    void setUp() {
        writer =
                new MatchWriter(Clock.fixed(NOW, ZoneOffset.UTC), matches, glMatches, bankMatches, transactions, items);
        recon = reconciliation();
    }

    private static LedgerLine ledgerLine(String signedAmount) {
        return new LedgerLine(
                UUIDv7Generator.generate(),
                UUIDv7Generator.generate(),
                "JE-1",
                END,
                amount(signedAmount),
                null,
                null,
                false);
    }

    private static Header header(MatchState state) {
        return new Header(
                MatchKind.MANY_TO_ONE,
                state,
                MatchOrigin.USER,
                85,
                List.of("EXACT_AMOUNT"),
                "split deposit",
                UUIDv7Generator.generate(),
                null);
    }

    private static BankReconciliationOutstandingItem ledgerItem(UUID glLineId) {
        BankReconciliationOutstandingItem open =
                item(OutstandingItemSide.LEDGER, OutstandingItemKind.OUTSTANDING_CHECK, "-40.0000", START);
        open.setGlLineId(glLineId);
        return open;
    }

    private void saveAssignsId(UUID matchId) {
        when(matches.save(any(BankReconciliationMatch.class))).thenAnswer(inv -> {
            BankReconciliationMatch match = inv.getArgument(0);
            match.setMatchId(matchId);
            return match;
        });
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("an ACCEPTED match writes header, members, MATCHED rows and clears the lines' OPEN ledger items")
        void acceptedMatch() {
            UUID matchId = UUIDv7Generator.generate();
            saveAssignsId(matchId);
            BankTransaction early = transaction("30.0000", END.minusDays(4));
            BankTransaction late = transaction("10.0000", END.minusDays(1));
            LedgerLine line = ledgerLine("39.9900");
            UUID replacedMatchId = UUIDv7Generator.generate();
            List<LedgerMember> members = List.of(LedgerMember.of(line));
            BankReconciliationOutstandingItem ledgerOpen = ledgerItem(line.lineId());
            BankReconciliationOutstandingItem bankOpen =
                    item(OutstandingItemSide.BANK, OutstandingItemKind.DEPOSIT_IN_TRANSIT, "40.0000", START);
            when(items.findByGlLineIdInAndStatus(List.of(line.lineId()), OutstandingItemStatus.OPEN))
                    .thenReturn(List.of(ledgerOpen, bankOpen));
            Header header = new Header(
                    MatchKind.MANY_TO_ONE,
                    MatchState.ACCEPTED,
                    MatchOrigin.USER,
                    85,
                    List.of("EXACT_AMOUNT"),
                    "split deposit",
                    UUIDv7Generator.generate(),
                    replacedMatchId);

            BankReconciliationMatch result = writer.create(recon, header, List.of(early, late), members, ACTOR);

            verify(matches).save(savedMatch.capture());
            BankReconciliationMatch match = savedMatch.getValue();
            assertThat(result).isSameAs(match);
            assertThat(match.getMatchId()).isEqualTo(matchId);
            assertThat(match.getReconciliationId()).isEqualTo(RECON_ID);
            assertThat(match.getMatchKind()).isEqualTo(MatchKind.MANY_TO_ONE);
            assertThat(match.getState()).isEqualTo(MatchState.ACCEPTED);
            assertThat(match.getOrigin()).isEqualTo(MatchOrigin.USER);
            assertThat(match.getConfidenceScore()).isEqualTo(85);
            assertThat(match.getReasons()).containsExactly("EXACT_AMOUNT");
            assertThat(match.getJustification()).isEqualTo("split deposit");
            assertThat(match.getRequestId()).isEqualTo(header.requestId());
            assertThat(match.getReplacesMatchId()).isEqualTo(replacedMatchId);
            assertThat(match.getBankTotal()).isEqualByComparingTo("40.0000");
            assertThat(match.getLedgerTotal()).isEqualByComparingTo("39.9900");
            assertThat(match.getToleranceUsed()).isEqualByComparingTo("0.0100");
            assertThat(match.getProposedBy()).isEqualTo(ACTOR);
            assertThat(match.getProposedAt()).isEqualTo(NOW);
            assertThat(match.getAcceptedBy()).isEqualTo(ACTOR);
            assertThat(match.getAcceptedAt()).isEqualTo(NOW);

            verify(bankMatches).saveAll(savedBankMembers.capture());
            assertThat(savedBankMembers.getValue())
                    .extracting(
                            BankReconciliationBankMatch::getMatchId, BankReconciliationBankMatch::getBankTransactionId)
                    .containsExactly(
                            tuple(matchId, early.getBankTransactionId()), tuple(matchId, late.getBankTransactionId()));
            verify(glMatches).saveAllAndFlush(savedGlMembers.capture());
            assertThat(savedGlMembers.getValue()).singleElement().satisfies(gl -> {
                assertThat(gl.getReconciliationId()).isEqualTo(RECON_ID);
                assertThat(gl.getMatchId()).isEqualTo(matchId);
                assertThat(gl.getGlLineId()).isEqualTo(line.lineId());
                assertThat(gl.getSignedAmount()).isEqualByComparingTo("39.9900");
                assertThat(gl.isActive()).isTrue();
            });

            assertThat(early.getStatus()).isEqualTo(BankTransactionStatus.MATCHED);
            assertThat(late.getStatus()).isEqualTo(BankTransactionStatus.MATCHED);
            verify(transactions).saveAll(List.of(early, late));

            verify(items).saveAll(savedItems.capture());
            assertThat(savedItems.getValue()).containsExactly(ledgerOpen);
            assertThat(ledgerOpen.getStatus()).isEqualTo(OutstandingItemStatus.CLEARED);
            assertThat(ledgerOpen.getClearedInReconciliationId()).isEqualTo(RECON_ID);
            assertThat(ledgerOpen.getClearedByMatchId()).isEqualTo(matchId);
            assertThat(ledgerOpen.getClearedAt()).isEqualTo(NOW);
            assertThat(ledgerOpen.getClearedBy()).isEqualTo(ACTOR);
            assertThat(ledgerOpen.getClosedOn()).isEqualTo(late.getTransactionDate());
            assertThat(bankOpen.getStatus()).isEqualTo(OutstandingItemStatus.OPEN);
        }

        @Test
        @DisplayName("a PROPOSED match only reserves its members")
        void proposedMatch() {
            UUID matchId = UUIDv7Generator.generate();
            saveAssignsId(matchId);
            BankTransaction bank = transaction("25.0000", END);
            LedgerLine line = ledgerLine("25.0000");

            BankReconciliationMatch result = writer.create(
                    recon, header(MatchState.PROPOSED), List.of(bank), List.of(LedgerMember.of(line)), ACTOR);

            assertThat(result.getState()).isEqualTo(MatchState.PROPOSED);
            assertThat(result.getProposedBy()).isEqualTo(ACTOR);
            assertThat(result.getAcceptedBy()).isNull();
            assertThat(result.getAcceptedAt()).isNull();
            assertThat(result.getToleranceUsed()).isEqualByComparingTo("0");
            assertThat(bank.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
            verify(bankMatches).saveAll(anyList());
            verify(glMatches).saveAllAndFlush(anyList());
            verifyNoInteractions(transactions, items);
        }

        @Test
        @DisplayName("a lost race on the active partial uniques is 409 RECONCILIATION_LINE_INELIGIBLE")
        void concurrentMatchIsIneligible() {
            saveAssignsId(UUIDv7Generator.generate());
            BankTransaction bank = transaction("25.0000", END);
            LedgerLine line = ledgerLine("25.0000");
            when(glMatches.saveAllAndFlush(anyList())).thenThrow(new DataIntegrityViolationException("uq_active"));

            assertThatThrownBy(() -> writer.create(
                            recon, header(MatchState.ACCEPTED), List.of(bank), List.of(LedgerMember.of(line)), ACTOR))
                    .isInstanceOf(ReconciliationLineIneligibleException.class)
                    .hasMessageContaining("concurrently matched");
            assertThat(bank.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
            verifyNoInteractions(transactions, items);
        }
    }

    @Nested
    @DisplayName("accept")
    class Accept {

        @Test
        @DisplayName("with no bank rows, closes cleared items on the statement end date")
        void noBankRowsClosesOnStatementEnd() {
            BankReconciliationMatch match = new BankReconciliationMatch();
            match.setMatchId(UUIDv7Generator.generate());
            UUID glLineId = UUIDv7Generator.generate();
            BankReconciliationOutstandingItem open = ledgerItem(glLineId);
            when(items.findByGlLineIdInAndStatus(List.of(glLineId), OutstandingItemStatus.OPEN))
                    .thenReturn(List.of(open));

            writer.accept(recon, match, List.of(), List.of(glLineId), ACTOR);

            assertThat(open.getStatus()).isEqualTo(OutstandingItemStatus.CLEARED);
            assertThat(open.getClosedOn()).isEqualTo(END);
            assertThat(open.getClearedByMatchId()).isEqualTo(match.getMatchId());
            verify(transactions).saveAll(List.of());
            verify(items).saveAll(List.of(open));
        }
    }

    @Nested
    @DisplayName("reopenCleared")
    class ReopenCleared {

        @Test
        @DisplayName("re-opens the items the match cleared, wiping every closure field")
        void reopens() {
            UUID matchId = UUIDv7Generator.generate();
            BankReconciliationOutstandingItem cleared = ledgerItem(UUIDv7Generator.generate());
            cleared.setStatus(OutstandingItemStatus.CLEARED);
            cleared.setClearedInReconciliationId(RECON_ID);
            cleared.setClearedByMatchId(matchId);
            cleared.setClearedAt(NOW);
            cleared.setClearedBy(ACTOR);
            cleared.setClosedOn(END);
            when(items.findByClearedByMatchIdAndStatus(matchId, OutstandingItemStatus.CLEARED))
                    .thenReturn(List.of(cleared));

            MatchWriter.reopenCleared(items, matchId);

            assertThat(cleared.getStatus()).isEqualTo(OutstandingItemStatus.OPEN);
            assertThat(cleared.getClearedInReconciliationId()).isNull();
            assertThat(cleared.getClearedByMatchId()).isNull();
            assertThat(cleared.getClearedAt()).isNull();
            assertThat(cleared.getClearedBy()).isNull();
            assertThat(cleared.getClosedOn()).isNull();
            verify(items).saveAll(List.of(cleared));
        }
    }

    @Nested
    @DisplayName("end")
    class End {

        private BankReconciliationMatch match;
        private BankTransaction row;
        private BankReconciliationBankMatch bankMember;
        private BankReconciliationGlMatch glMember;

        @BeforeEach
        void liveMatch() {
            match = new BankReconciliationMatch();
            match.setMatchId(UUIDv7Generator.generate());
            match.setState(MatchState.ACCEPTED);
            row = transaction("25.0000", END);
            row.setStatus(BankTransactionStatus.MATCHED);
            bankMember = new BankReconciliationBankMatch(match.getMatchId(), row.getBankTransactionId());
            glMember = new BankReconciliationGlMatch();
            glMember.setMatchId(match.getMatchId());
            glMember.setGlLineId(UUIDv7Generator.generate());
            glMember.setActive(true);
            when(bankMatches.findByMatchIdAndActiveTrue(match.getMatchId())).thenReturn(List.of(bankMember));
            when(transactions.findAllById(List.of(row.getBankTransactionId()))).thenReturn(List.of(row));
            when(glMatches.findByMatchIdAndActiveTrue(match.getMatchId())).thenReturn(List.of(glMember));
        }

        private void assertMembersReleased() {
            assertThat(row.getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
            assertThat(bankMember.isActive()).isFalse();
            assertThat(glMember.isActive()).isFalse();
            verify(transactions).saveAll(List.of(row));
            verify(bankMatches).saveAll(List.of(bankMember));
            verify(glMatches).saveAllAndFlush(List.of(glMember));
            verify(matches).save(match);
        }

        @Test
        @DisplayName("an unmatch releases members, re-opens cleared items and records who, when and why")
        void unmatch() {
            BankReconciliationOutstandingItem cleared = ledgerItem(glMember.getGlLineId());
            cleared.setStatus(OutstandingItemStatus.CLEARED);
            cleared.setClearedByMatchId(match.getMatchId());
            when(items.findByClearedByMatchIdAndStatus(match.getMatchId(), OutstandingItemStatus.CLEARED))
                    .thenReturn(List.of(cleared));

            writer.end(match, MatchState.UNMATCHED, "wrong pairing", ACTOR);

            assertMembersReleased();
            assertThat(match.getState()).isEqualTo(MatchState.UNMATCHED);
            assertThat(match.getUnmatchedBy()).isEqualTo(ACTOR);
            assertThat(match.getUnmatchedAt()).isEqualTo(NOW);
            assertThat(match.getUnmatchReason()).isEqualTo("wrong pairing");
            assertThat(match.getRejectedBy()).isNull();
            assertThat(cleared.getStatus()).isEqualTo(OutstandingItemStatus.OPEN);
            assertThat(cleared.getClearedByMatchId()).isNull();
        }

        @Test
        @DisplayName("a rejection releases members and records the rejecter, leaving items alone")
        void reject() {
            writer.end(match, MatchState.REJECTED, null, ACTOR);

            assertMembersReleased();
            assertThat(match.getState()).isEqualTo(MatchState.REJECTED);
            assertThat(match.getRejectedBy()).isEqualTo(ACTOR);
            assertThat(match.getRejectedAt()).isEqualTo(NOW);
            assertThat(match.getUnmatchedBy()).isNull();
            verifyNoInteractions(items);
        }

        @Test
        @DisplayName("a break releases members and records only the new state")
        void broken() {
            writer.end(match, MatchState.BROKEN, null, ACTOR);

            assertMembersReleased();
            assertThat(match.getState()).isEqualTo(MatchState.BROKEN);
            assertThat(match.getRejectedBy()).isNull();
            assertThat(match.getUnmatchedBy()).isNull();
            verifyNoInteractions(items);
        }
    }
}
