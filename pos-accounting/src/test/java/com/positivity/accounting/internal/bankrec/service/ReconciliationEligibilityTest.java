package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.entry;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.item;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.line;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.postedLine;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.ReconciliationLineIneligibleException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ReconciliationEligibility} (SPEC §3.4 M3/M4, §3.6 O1, D19; story S4, #2303): which bank rows and ledger
 * lines a match or an outstanding item may name, every ineligibility reason, the {@code exceptMatchId} carve-out,
 * and the not-found / wrong-account paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationEligibility (#2303)")
class ReconciliationEligibilityTest {

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private JournalEntryLineRepository lines;

    @Mock
    private BankReconciliationBankMatchRepository bankMatches;

    @Mock
    private BankReconciliationGlMatchRepository glMatches;

    @Mock
    private BankReconciliationOutstandingItemRepository items;

    @Mock
    private AccountingAuditLogRepository auditLogs;

    private ReconciliationEligibility eligibility;
    private BankReconciliation recon;

    @BeforeEach
    void setUp() {
        eligibility = new ReconciliationEligibility(transactions, lines, bankMatches, glMatches, items, auditLogs);
        recon = reconciliation();
    }

    private static BankReconciliationBankMatch bankMember(UUID matchId, UUID bankTransactionId) {
        return new BankReconciliationBankMatch(matchId, bankTransactionId);
    }

    private static BankReconciliationGlMatch glMember(UUID matchId, UUID glLineId) {
        BankReconciliationGlMatch member = new BankReconciliationGlMatch();
        member.setMatchId(matchId);
        member.setGlLineId(glLineId);
        return member;
    }

    private static AccountingAuditLog log(String operation) {
        AccountingAuditLog log = new AccountingAuditLog();
        log.setOperation(operation);
        return log;
    }

    @Nested
    @DisplayName("lockBankForMatch")
    class LockBankForMatch {

        private void lock(BankTransaction... rows) {
            when(transactions.lockByIds(anyCollection())).thenReturn(List.of(rows));
        }

        @Test
        @DisplayName("returns the eligible rows by date, ignoring the match being replaced")
        void eligibleRowsSortedAndExceptMatchIgnored() {
            BankTransaction onEnd = transaction("25.0000", END);
            BankTransaction carriedIn = transaction("10.0000", START.minusDays(5));
            UUID replacedMatch = UUIDv7Generator.generate();
            List<UUID> ids = List.of(onEnd.getBankTransactionId(), carriedIn.getBankTransactionId());
            lock(onEnd, carriedIn);
            when(bankMatches.findByBankTransactionIdInAndActiveTrue(ids))
                    .thenReturn(List.of(bankMember(replacedMatch, onEnd.getBankTransactionId())));
            when(items.findByBankTransactionIdInAndStatus(ids, OutstandingItemStatus.OPEN))
                    .thenReturn(List.of());

            List<BankTransaction> result = eligibility.lockBankForMatch(recon, ids, replacedMatch);

            assertThat(result).containsExactly(carriedIn, onEnd);
        }

        @Test
        @DisplayName("locks each id once even when the request repeats it")
        void duplicateIdsLockedOnce() {
            BankTransaction row = transaction("25.0000", END);
            List<UUID> ids = List.of(row.getBankTransactionId(), row.getBankTransactionId());
            lock(row);
            when(bankMatches.findByBankTransactionIdInAndActiveTrue(ids)).thenReturn(List.of());
            when(items.findByBankTransactionIdInAndStatus(ids, OutstandingItemStatus.OPEN))
                    .thenReturn(List.of());

            assertThat(eligibility.lockBankForMatch(recon, ids, null)).containsExactly(row);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> locked = ArgumentCaptor.forClass(Collection.class);
            verify(transactions).lockByIds(locked.capture());
            assertThat(locked.getValue()).containsExactly(row.getBankTransactionId());
        }

        @Test
        @DisplayName("rejects a row that is not UNMATCHED")
        void notUnmatched() {
            BankTransaction row = transaction("25.0000", END);
            row.setStatus(BankTransactionStatus.MATCHED);
            List<UUID> ids = List.of(row.getBankTransactionId());
            lock(row);

            assertThatThrownBy(() -> eligibility.lockBankForMatch(recon, ids, null))
                    .isInstanceOf(ReconciliationLineIneligibleException.class)
                    .hasMessageContaining("is MATCHED, not UNMATCHED");
        }

        @Test
        @DisplayName("rejects a PENDING row: never matchable (D19)")
        void pendingSettlement() {
            BankTransaction row = transaction("25.0000", END);
            row.setSettlementState(SettlementState.PENDING);
            List<UUID> ids = List.of(row.getBankTransactionId());
            lock(row);

            assertThatThrownBy(() -> eligibility.lockBankForMatch(recon, ids, null))
                    .isInstanceOf(ReconciliationLineIneligibleException.class)
                    .hasMessageContaining("PENDING and never matchable");
        }

        @Test
        @DisplayName("rejects a row dated after the window end")
        void afterWindowEnd() {
            BankTransaction row = transaction("25.0000", END.plusDays(1));
            List<UUID> ids = List.of(row.getBankTransactionId());
            lock(row);

            assertThatThrownBy(() -> eligibility.lockBankForMatch(recon, ids, null))
                    .isInstanceOf(ReconciliationLineIneligibleException.class)
                    .hasMessageContaining("dated after the window end " + END);
        }

        @Test
        @DisplayName("rejects a row in another active match")
        void inAnotherMatch() {
            BankTransaction row = transaction("25.0000", END);
            List<UUID> ids = List.of(row.getBankTransactionId());
            lock(row);
            when(bankMatches.findByBankTransactionIdInAndActiveTrue(ids))
                    .thenReturn(List.of(bankMember(UUIDv7Generator.generate(), row.getBankTransactionId())));
            when(items.findByBankTransactionIdInAndStatus(ids, OutstandingItemStatus.OPEN))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> eligibility.lockBankForMatch(recon, ids, UUIDv7Generator.generate()))
                    .isInstanceOf(ReconciliationLineIneligibleException.class)
                    .hasMessageContaining("already in an active match");
        }

        @Test
        @DisplayName("rejects a row in an OPEN outstanding item")
        void inOpenItem() {
            BankTransaction row = transaction("25.0000", END);
            List<UUID> ids = List.of(row.getBankTransactionId());
            BankReconciliationOutstandingItem open =
                    item(OutstandingItemSide.BANK, OutstandingItemKind.DEPOSIT_IN_TRANSIT, "25.0000", END);
            open.setBankTransactionId(row.getBankTransactionId());
            lock(row);
            when(bankMatches.findByBankTransactionIdInAndActiveTrue(ids)).thenReturn(List.of());
            when(items.findByBankTransactionIdInAndStatus(ids, OutstandingItemStatus.OPEN))
                    .thenReturn(List.of(open));

            assertThatThrownBy(() -> eligibility.lockBankForMatch(recon, ids, null))
                    .isInstanceOf(ReconciliationLineIneligibleException.class)
                    .hasMessageContaining("OPEN outstanding item");
        }
    }

    @Nested
    @DisplayName("lockBank")
    class LockBank {

        @Test
        @DisplayName("404 BANK_TRANSACTION_NOT_FOUND for a row on another account")
        void rowOnAnotherAccount() {
            BankTransaction foreign = transaction("25.0000", END);
            foreign.setGlAccountId(UUIDv7Generator.generate());
            when(transactions.lockByIds(anyCollection())).thenReturn(List.of(foreign));

            assertThatThrownBy(() -> eligibility.lockBank(recon, List.of(foreign.getBankTransactionId())))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.BANK_TRANSACTION_NOT_FOUND))
                    .hasMessageContaining("account 1000");
        }

        @Test
        @DisplayName("404 BANK_TRANSACTION_NOT_FOUND for an unknown id")
        void unknownId() {
            BankTransaction known = transaction("25.0000", END);
            when(transactions.lockByIds(anyCollection())).thenReturn(List.of(known));

            assertThatThrownBy(() -> eligibility.lockBank(
                            recon, List.of(known.getBankTransactionId(), UUIDv7Generator.generate())))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.BANK_TRANSACTION_NOT_FOUND));
        }

        @Test
        @DisplayName("an empty request locks nothing and returns nothing")
        void emptyIds() {
            when(transactions.lockByIds(anyCollection())).thenReturn(List.of());

            assertThat(eligibility.lockBank(recon, List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("lockLedgerForMatch")
    class LockLedgerForMatch {

        private void lock(JournalEntryLine... locked) {
            when(lines.lockByIds(anyCollection())).thenReturn(List.of(locked));
        }

        @Test
        @DisplayName("returns POSTED lines by id, ignoring the match being replaced")
        void eligibleLinesSortedAndExceptMatchIgnored() {
            JournalEntryLine first = postedLine("25.0000", END);
            JournalEntryLine second = postedLine("-10.0000", START.minusDays(3));
            UUID replacedMatch = UUIDv7Generator.generate();
            List<UUID> ids = List.of(second.getLineId(), first.getLineId());
            lock(second, first);
            when(glMatches.findByGlLineIdInAndActiveTrue(ids))
                    .thenReturn(List.of(glMember(replacedMatch, first.getLineId())));

            List<JournalEntryLine> result = eligibility.lockLedgerForMatch(recon, ids, replacedMatch);

            assertThat(result).containsExactly(first, second);
        }

        @Test
        @DisplayName("rejects a line with no entry")
        void noEntry() {
            JournalEntryLine orphan = new JournalEntryLine();
            orphan.setLineId(UUIDv7Generator.generate());
            orphan.setGlAccountId(recon.getGlAccountId());
            List<UUID> ids = List.of(orphan.getLineId());
            lock(orphan);
            when(glMatches.findByGlLineIdInAndActiveTrue(ids)).thenReturn(List.of());

            assertThatThrownBy(() -> eligibility.lockLedgerForMatch(recon, ids, null))
                    .isInstanceOf(ReconciliationLineIneligibleException.class)
                    .hasMessageContaining("is not on a POSTED entry");
        }

        @Test
        @DisplayName("rejects a line of an entry that is not POSTED")
        void entryNotPosted() {
            JournalEntryLine draft = line(entry(END, JournalEntryStatus.DRAFT), "25.0000");
            List<UUID> ids = List.of(draft.getLineId());
            lock(draft);
            when(glMatches.findByGlLineIdInAndActiveTrue(ids)).thenReturn(List.of());

            assertThatThrownBy(() -> eligibility.lockLedgerForMatch(recon, ids, null))
                    .isInstanceOf(ReconciliationLineIneligibleException.class)
                    .hasMessageContaining("is not on a POSTED entry");
        }

        @Test
        @DisplayName("rejects a line dated after the window end (M3)")
        void afterWindowEnd() {
            JournalEntryLine late = postedLine("25.0000", END.plusDays(1));
            List<UUID> ids = List.of(late.getLineId());
            lock(late);
            when(glMatches.findByGlLineIdInAndActiveTrue(ids)).thenReturn(List.of());

            assertThatThrownBy(() -> eligibility.lockLedgerForMatch(recon, ids, null))
                    .isInstanceOf(ReconciliationLineIneligibleException.class)
                    .hasMessageContaining("dated after the window end " + END)
                    .hasMessageContaining("(M3)");
        }

        @Test
        @DisplayName("rejects a line in another active match")
        void inAnotherMatch() {
            JournalEntryLine matched = postedLine("25.0000", END);
            List<UUID> ids = List.of(matched.getLineId());
            lock(matched);
            when(glMatches.findByGlLineIdInAndActiveTrue(ids))
                    .thenReturn(List.of(glMember(UUIDv7Generator.generate(), matched.getLineId())));

            assertThatThrownBy(() -> eligibility.lockLedgerForMatch(recon, ids, null))
                    .isInstanceOf(ReconciliationLineIneligibleException.class)
                    .hasMessageContaining("already matched in a reconciliation");
        }
    }

    @Nested
    @DisplayName("lockLedger")
    class LockLedger {

        @Test
        @DisplayName("404 for an unknown line")
        void unknownLine() {
            JournalEntryLine known = postedLine("25.0000", END);
            when(lines.lockByIds(anyCollection())).thenReturn(List.of(known));

            assertThatThrownBy(
                            () -> eligibility.lockLedger(recon, List.of(known.getLineId(), UUIDv7Generator.generate())))
                    .isInstanceOf(ReconciliationNotFoundException.class)
                    .hasMessageContaining("were not found");
        }

        @Test
        @DisplayName("lockLedgerForMatch 404s on an unknown line before looking up active GL matches")
        void unknownLineForMatch_failsBeforeMatchLookup() {
            JournalEntryLine known = postedLine("25.0000", END);
            when(lines.lockByIds(anyCollection())).thenReturn(List.of(known));

            assertThatThrownBy(() -> eligibility.lockLedgerForMatch(
                            recon, List.of(known.getLineId(), UUIDv7Generator.generate()), null))
                    .isInstanceOf(ReconciliationNotFoundException.class);
            verifyNoInteractions(glMatches);
        }

        @Test
        @DisplayName("400 for a line posting to another account")
        void lineOnAnotherAccount() {
            JournalEntryLine foreign =
                    line(entry(END, JournalEntryStatus.POSTED), "25.0000", UUIDv7Generator.generate());
            when(lines.lockByIds(anyCollection())).thenReturn(List.of(foreign));

            assertThatThrownBy(() -> eligibility.lockLedger(recon, List.of(foreign.getLineId())))
                    .isInstanceOf(InvalidRequestParameterException.class)
                    .hasMessageContaining("does not post to the reconciled account 1000");
        }

        @Test
        @DisplayName("locks each id once even when the request repeats it")
        void duplicateIdsLockedOnce() {
            JournalEntryLine only = postedLine("25.0000", END);
            when(lines.lockByIds(Set.of(only.getLineId()))).thenReturn(List.of(only));

            assertThat(eligibility.lockLedger(recon, List.of(only.getLineId(), only.getLineId())))
                    .containsExactly(only);
        }
    }

    @Nested
    @DisplayName("openItemBankIds")
    class OpenItemBankIds {

        @Test
        @DisplayName("collects the bank ids of OPEN items and drops ledger-side ones")
        void bankIdsOnly() {
            BankReconciliationOutstandingItem bankSide =
                    item(OutstandingItemSide.BANK, OutstandingItemKind.DEPOSIT_IN_TRANSIT, "25.0000", END);
            BankReconciliationOutstandingItem ledgerSide =
                    item(OutstandingItemSide.LEDGER, OutstandingItemKind.OUTSTANDING_CHECK, "-5.0000", END);
            List<UUID> ids = List.of(bankSide.getBankTransactionId());
            when(items.findByBankTransactionIdInAndStatus(ids, OutstandingItemStatus.OPEN))
                    .thenReturn(List.of(bankSide, ledgerSide));

            assertThat(eligibility.openItemBankIds(ids)).containsExactly(bankSide.getBankTransactionId());
        }
    }

    @Nested
    @DisplayName("anyFormerPossibleDuplicate")
    class AnyFormerPossibleDuplicate {

        @Test
        @DisplayName("true when a row carries a duplicate-review audit entry")
        void reviewedRow() {
            BankTransaction plain = transaction("25.0000", END);
            BankTransaction reviewed = transaction("25.0000", END);
            when(auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc(
                            BankRecAuditRecorder.BANK_TRANSACTION, plain.getBankTransactionId()))
                    .thenReturn(List.of(log("BANK_TRANSACTION_CREATE")));
            when(auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc(
                            BankRecAuditRecorder.BANK_TRANSACTION, reviewed.getBankTransactionId()))
                    .thenReturn(
                            List.of(log("BANK_TRANSACTION_CREATE"), log(ReconciliationEligibility.DUPLICATE_REVIEW)));

            assertThat(eligibility.anyFormerPossibleDuplicate(List.of(plain, reviewed)))
                    .isTrue();
        }

        @Test
        @DisplayName("false when no row was ever reviewed as a duplicate")
        void noReview() {
            BankTransaction row = transaction("25.0000", END);
            when(auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc(
                            BankRecAuditRecorder.BANK_TRANSACTION, row.getBankTransactionId()))
                    .thenReturn(List.of(log(null), log("BANK_TRANSACTION_CREATE")));

            assertThat(eligibility.anyFormerPossibleDuplicate(List.of(row))).isFalse();
        }

        @Test
        @DisplayName("false for no rows, without reading the audit log")
        void noRows() {
            assertThat(eligibility.anyFormerPossibleDuplicate(List.of())).isFalse();
            verifyNoInteractions(auditLogs);
        }
    }
}
