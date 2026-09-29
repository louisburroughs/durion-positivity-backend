package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.ACCOUNT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.STATEMENT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.statement;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.InvalidationReason;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link StatementSupersession} (SPEC §4.9 path 3, R2, O2, D15; story S5, #2304): the eligibility rules in their
 * order, and the retirement of the old statement — its rows excluded, its approved reconciliation invalidated
 * with its members released, its bank-side items released — with one {@code BANK_STATEMENT_SUPERSEDE} row.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementSupersession — corrected re-import (#2304)")
class StatementSupersessionTest {

    private static final String WHY = "The bank reissued September";
    private static final String ACTOR = "preparer";

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private BankStatementRepository statements;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private BankReconciliationRepository reconciliations;

    @Mock
    private BankReconciliationOutstandingItemRepository items;

    @Mock
    private ReconciliationLifecycle lifecycle;

    @Mock
    private BankRecAuditRecorder audit;

    private StatementSupersession supersession;
    private BankStatement old;

    @BeforeEach
    void setUp() {
        supersession =
                new StatementSupersession(clock, statements, transactions, reconciliations, items, lifecycle, audit);
        old = statement(STATEMENT_ID, START, END, null);
        lenient().when(statements.findById(STATEMENT_ID)).thenReturn(Optional.of(old));
    }

    private static void assertCode(Runnable call, BankRecErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        BankRecException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    @DisplayName("no supersession without an id; a justification without one is a VALIDATION_ERROR")
    void absent() {
        assertThat(supersession.requireEligible(ACCOUNT_ID, null, null)).isNull();
        assertCode(() -> supersession.requireEligible(ACCOUNT_ID, null, WHY), BankRecErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("the justification is checked first: blank is VALIDATION_ERROR, 1-9 characters JUSTIFICATION_REQUIRED")
    void justification() {
        assertCode(
                () -> supersession.requireEligible(ACCOUNT_ID, STATEMENT_ID, null), BankRecErrorCode.VALIDATION_ERROR);
        assertCode(
                () -> supersession.requireEligible(ACCOUNT_ID, STATEMENT_ID, "  "), BankRecErrorCode.VALIDATION_ERROR);
        assertCode(
                () -> supersession.requireEligible(ACCOUNT_ID, STATEMENT_ID, "too short"),
                BankRecErrorCode.JUSTIFICATION_REQUIRED);
    }

    @Test
    @DisplayName("unknown, another account's or a SUPERSEDED statement is STATEMENT_SUPERSESSION_NOT_ELIGIBLE (AC 6)")
    void notEligible() {
        UUID unknown = UUIDv7Generator.generate();
        when(statements.findById(unknown)).thenReturn(Optional.empty());
        assertCode(
                () -> supersession.requireEligible(ACCOUNT_ID, unknown, WHY),
                BankRecErrorCode.STATEMENT_SUPERSESSION_NOT_ELIGIBLE);
        assertCode(
                () -> supersession.requireEligible(UUIDv7Generator.generate(), STATEMENT_ID, WHY),
                BankRecErrorCode.STATEMENT_SUPERSESSION_NOT_ELIGIBLE);
        old.setStatus(BankStatementStatus.SUPERSEDED);
        assertCode(
                () -> supersession.requireEligible(ACCOUNT_ID, STATEMENT_ID, WHY),
                BankRecErrorCode.STATEMENT_SUPERSESSION_NOT_ELIGIBLE);
    }

    @Test
    @DisplayName("a statement with an IN_PROGRESS or SUBMITTED reconciliation is 409 WINDOW_ALREADY_RECONCILED (AC 6)")
    void activeReconciliation() {
        BankReconciliation active = reconciliation();
        when(reconciliations.findByStatementIdAndStatusIn(eq(STATEMENT_ID), anyCollection()))
                .thenReturn(List.of(active));

        assertCode(
                () -> supersession.requireEligible(ACCOUNT_ID, STATEMENT_ID, WHY),
                BankRecErrorCode.RECONCILIATION_WINDOW_ALREADY_RECONCILED);
    }

    @Test
    @DisplayName(
            "retire: statement SUPERSEDED, reconciliation invalidated and released, rows excluded, bank items released")
    void retire() {
        when(reconciliations.findByStatementIdAndStatusIn(eq(STATEMENT_ID), anyCollection()))
                .thenReturn(List.of());
        StatementSupersession.Request request = supersession.requireEligible(ACCOUNT_ID, STATEMENT_ID, WHY);
        BankReconciliation finalized = reconciliation();
        finalized.setStatus(ReconciliationStatus.FINALIZED);
        when(reconciliations.findByStatementIdAndStatusIn(eq(STATEMENT_ID), anyCollection()))
                .thenReturn(List.of(finalized));
        BankTransaction matched = transaction("10.00", END);
        matched.setStatus(BankTransactionStatus.MATCHED);
        BankTransaction duplicate = transaction("5.00", END);
        duplicate.setStatus(BankTransactionStatus.EXCLUDED);
        duplicate.setExclusionReason("Confirmed duplicate of the ACH row");
        when(transactions.findByStatementIdOrderBySourceRowNumberAsc(STATEMENT_ID))
                .thenReturn(List.of(matched, duplicate));
        BankReconciliationOutstandingItem bankItem = new BankReconciliationOutstandingItem();
        bankItem.setSide(OutstandingItemSide.BANK);
        bankItem.setStatus(OutstandingItemStatus.OPEN);
        when(items.findByBankTransactionIdInAndStatus(anyCollection(), eq(OutstandingItemStatus.OPEN)))
                .thenReturn(List.of(bankItem));

        supersession.retire(request, ACTOR);

        assertThat(old.getStatus()).isEqualTo(BankStatementStatus.SUPERSEDED);
        verify(statements).saveAndFlush(old);
        verify(lifecycle).invalidate(finalized, InvalidationReason.STATEMENT_SUPERSEDED, null, ACTOR);
        verify(lifecycle).releaseMembers(finalized);
        assertThat(matched.getStatus()).isEqualTo(BankTransactionStatus.EXCLUDED);
        assertThat(matched.getExclusionReason()).isEqualTo("STATEMENT_SUPERSEDED");
        assertThat(matched.getExcludedBy()).isEqualTo(ACTOR);
        assertThat(duplicate.getExclusionReason()).isEqualTo("Confirmed duplicate of the ACH row");
        assertThat(bankItem.getStatus()).isEqualTo(OutstandingItemStatus.RELEASED);
        assertThat(bankItem.getReleaseReason()).isEqualTo("STATEMENT_SUPERSEDED");
    }

    @Test
    @DisplayName(
            "link writes exactly one BANK_STATEMENT_SUPERSEDE row, COMMITTED to SUPERSEDED, with the justification [M]")
    void link() {
        when(reconciliations.findByStatementIdAndStatusIn(eq(STATEMENT_ID), anyCollection()))
                .thenReturn(List.of());
        StatementSupersession.Request request = supersession.requireEligible(ACCOUNT_ID, STATEMENT_ID, WHY);
        UUID corrected = UUIDv7Generator.generate();

        supersession.link(request, corrected, ACTOR);

        assertThat(old.getSupersededByStatementId()).isEqualTo(corrected);
        verify(audit)
                .record(
                        BankRecAuditRecorder.BANK_STATEMENT,
                        STATEMENT_ID,
                        BankRecAuditRecorder.BANK_STATEMENT_SUPERSEDE,
                        ACTOR,
                        WHY,
                        "COMMITTED",
                        "SUPERSEDED");
        verify(statements).save(any(BankStatement.class));
    }
}
