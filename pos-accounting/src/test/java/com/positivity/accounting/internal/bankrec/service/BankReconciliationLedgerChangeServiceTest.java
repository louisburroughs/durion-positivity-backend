package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.ACCOUNT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.enums.InvalidationReason;
import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.event.LedgerPostingApplied;
import com.positivity.accounting.internal.event.LedgerReversalApplied;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link BankReconciliationLedgerChangeService} (SPEC §5.5, §3.6, §4.9 path 2, D11; story S5, #2304): a posting
 * invalidates the approved window it lands in; a reversal breaks the matches on the original's lines, voids its
 * open items with {@code closedOn} = the reversal date and invalidates the approvals resting on them.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankReconciliationLedgerChangeService — the ledger-change hook (#2304)")
class BankReconciliationLedgerChangeServiceTest {

    private static final String ACTOR = "clerk";
    private static final LocalDate REVERSAL_DATE = LocalDate.of(2026, 10, 3);

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private BankReconciliationRepository reconciliations;

    @Mock
    private BankReconciliationMatchRepository matches;

    @Mock
    private BankReconciliationGlMatchRepository glMatches;

    @Mock
    private BankReconciliationBankMatchRepository bankMatches;

    @Mock
    private BankReconciliationOutstandingItemRepository items;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private MatchWriter writer;

    @Mock
    private BankRecAuditRecorder audit;

    @Mock
    private BankReconciliationFacts facts;

    private BankReconciliationLedgerChangeService hook;
    private BankReconciliation finalized;

    @BeforeEach
    void setUp() {
        ReconciliationLifecycle lifecycle = new ReconciliationLifecycle(
                clock, reconciliations, matches, glMatches, bankMatches, transactions, items, audit, facts);
        hook = new BankReconciliationLedgerChangeService(reconciliations, matches, glMatches, items, writer, lifecycle);
        finalized = reconciliation();
        finalized.setStatus(ReconciliationStatus.FINALIZED);
        lenient().when(reconciliations.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a posting inside a FINALIZED window invalidates it with LEDGER_LINE_POSTED (AC 12)")
    void postingInvalidates() {
        UUID entry = UUIDv7Generator.generate();
        BankReconciliation submitted = reconciliation();
        submitted.setReconciliationId(UUIDv7Generator.generate());
        submitted.setStatus(ReconciliationStatus.SUBMITTED);
        when(reconciliations.lockCovering(eq(Set.of(ACCOUNT_ID)), anyCollection(), eq(END)))
                .thenReturn(List.of(finalized, submitted));

        hook.onPosted(new LedgerPostingApplied(entry, END, Set.of(ACCOUNT_ID), ACTOR));

        assertThat(finalized.getStatus()).isEqualTo(ReconciliationStatus.INVALIDATED);
        assertThat(finalized.getInvalidationReason()).isEqualTo("LEDGER_LINE_POSTED");
        assertThat(finalized.getInvalidatedByJournalEntryId()).isEqualTo(entry);
        assertThat(finalized.getInvalidatedAt()).isEqualTo(Instant.now(clock));
        assertThat(submitted.getStatus()).isEqualTo(ReconciliationStatus.SUBMITTED);
        verify(audit)
                .record(
                        BankRecAuditRecorder.BANK_RECONCILIATION,
                        RECON_ID,
                        BankRecAuditRecorder.RECONCILIATION_INVALIDATE,
                        ACTOR,
                        null,
                        "status=FINALIZED",
                        "status=INVALIDATED;reason=LEDGER_LINE_POSTED;journalEntryId=" + entry);
        verify(facts).invalidated(finalized, InvalidationReason.LEDGER_LINE_POSTED, entry, ACTOR);
    }

    @Test
    @DisplayName("a posting outside every approved window changes nothing")
    void postingOutsideChangesNothing() {
        when(reconciliations.lockCovering(anyCollection(), anyCollection(), any()))
                .thenReturn(List.of());

        hook.onPosted(new LedgerPostingApplied(UUIDv7Generator.generate(), END, Set.of(ACCOUNT_ID), ACTOR));

        verify(facts, never()).invalidated(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a reversal breaks the match, voids the item and invalidates the owner (AC 10, 11)")
    void reversal() {
        UUID original = UUIDv7Generator.generate();
        UUID reversal = UUIDv7Generator.generate();
        UUID matchedLine = UUIDv7Generator.generate();
        UUID itemLine = UUIDv7Generator.generate();
        BankReconciliationMatch match = new BankReconciliationMatch();
        match.setMatchId(UUIDv7Generator.generate());
        match.setReconciliationId(RECON_ID);
        match.setMatchKind(MatchKind.ONE_TO_ONE);
        match.setState(MatchState.ACCEPTED);
        BankReconciliationGlMatch member = new BankReconciliationGlMatch();
        member.setMatchId(match.getMatchId());
        member.setGlLineId(matchedLine);
        when(glMatches.findByGlLineIdInAndActiveTrue(List.of(matchedLine, itemLine)))
                .thenReturn(List.of(member));
        when(matches.findAllById(Set.of(match.getMatchId()))).thenReturn(List.of(match));
        when(reconciliations.lockByIds(Set.of(RECON_ID))).thenReturn(List.of(finalized));
        when(reconciliations.lockCovering(anyCollection(), anyCollection(), eq(REVERSAL_DATE)))
                .thenReturn(List.of());
        BankReconciliationOutstandingItem item = new BankReconciliationOutstandingItem();
        item.setGlLineId(itemLine);
        item.setStatus(OutstandingItemStatus.OPEN);
        when(items.findByGlLineIdInAndStatus(List.of(matchedLine, itemLine), OutstandingItemStatus.OPEN))
                .thenReturn(List.of(item));

        hook.onReversed(new LedgerReversalApplied(
                original, reversal, REVERSAL_DATE, List.of(matchedLine, itemLine), Set.of(ACCOUNT_ID), ACTOR, null));

        assertThat(match.getBrokenByJournalEntryId()).isEqualTo(reversal);
        verify(writer).end(match, MatchState.BROKEN, null, ACTOR);
        assertThat(item.getStatus()).isEqualTo(OutstandingItemStatus.VOIDED);
        assertThat(item.getVoidedByJournalEntryId()).isEqualTo(reversal);
        assertThat(item.getClosedOn()).isEqualTo(REVERSAL_DATE);
        assertThat(finalized.getStatus()).isEqualTo(ReconciliationStatus.INVALIDATED);
        assertThat(finalized.getInvalidationReason()).isEqualTo("LEDGER_LINE_REVERSED");
        assertThat(finalized.getInvalidatedByJournalEntryId()).isEqualTo(reversal);
        verify(facts).invalidated(finalized, InvalidationReason.LEDGER_LINE_REVERSED, reversal, ACTOR);
    }

    @Test
    @DisplayName("a reversal of an unmatched line in an open reconciliation invalidates nothing (D11)")
    void reversalOfAnUnmatchedLine() {
        UUID line = UUIDv7Generator.generate();
        when(glMatches.findByGlLineIdInAndActiveTrue(List.of(line))).thenReturn(List.of());
        when(reconciliations.lockCovering(anyCollection(), anyCollection(), any()))
                .thenReturn(List.of());
        when(items.findByGlLineIdInAndStatus(List.of(line), OutstandingItemStatus.OPEN))
                .thenReturn(List.of());

        hook.onReversed(new LedgerReversalApplied(
                UUIDv7Generator.generate(),
                UUIDv7Generator.generate(),
                REVERSAL_DATE,
                List.of(line),
                Set.of(ACCOUNT_ID),
                ACTOR,
                null));

        verify(writer, never()).end(any(), any(), any(), any());
        verify(facts, never()).invalidated(any(), any(), any(), any());
    }
}
