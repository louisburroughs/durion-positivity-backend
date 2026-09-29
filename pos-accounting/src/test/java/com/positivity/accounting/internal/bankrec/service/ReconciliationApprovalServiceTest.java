package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.STATEMENT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.amount;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.statement;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.terms;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.transaction;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationApiStatus;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReasonRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationTransitionRequest;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchOrigin;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.MatchWriter.Header;
import com.positivity.accounting.internal.bankrec.service.ReconciliationEquation.Terms;
import com.positivity.accounting.internal.exception.ReconciliationAlreadyFinalizedException;
import com.positivity.accounting.internal.exception.ReconciliationNotBalancedException;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link ReconciliationApprovalServiceImpl} (SPEC §3.8, §4.9, D2, D3; story S5, #2304): the transition table
 * with its §4.10 codes, the gate E4 in its order and its payload, the opening difference that never gates [M],
 * the self-approval switch and its audit row, the approval snapshots, cancel and supersede.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationApprovalServiceImpl — submit, approve, return, cancel, supersede (#2304)")
class ReconciliationApprovalServiceTest {

    private static final String PREPARER = "preparer";
    private static final String APPROVER = "approver";
    private static final String WHY = "Ledger changed after approval";

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private BankReconciliationRepository reconciliations;

    @Mock
    private ReconciliationCalculator calculator;

    @Mock
    private BankRecPolicy policy;

    @Mock
    private MatchWriter writer;

    @Mock
    private BankReconciliationMatchRepository matches;

    @Mock
    private BankReconciliationGlMatchRepository glMatches;

    @Mock
    private BankReconciliationBankMatchRepository bankMatches;

    @Mock
    private BankReconciliationOutstandingItemRepository items;

    @Mock
    private BankStatementRepository statements;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private BankRecAuditRecorder audit;

    @Mock
    private BankReconciliationFacts facts;

    private ReconciliationApprovalServiceImpl service;
    private BankReconciliation recon;

    @BeforeEach
    void setUp() {
        ReconciliationLifecycle lifecycle = new ReconciliationLifecycle(
                clock, reconciliations, matches, glMatches, bankMatches, transactions, items, audit, facts);
        service = new ReconciliationApprovalServiceImpl(
                new ReconciliationSupport(reconciliations, calculator, clock),
                new ApprovalGate(usd()),
                policy,
                lifecycle,
                writer,
                reconciliations,
                matches,
                items,
                statements,
                transactions,
                audit,
                facts);
        recon = reconciliation();
        recon.setVersion(3L);
        lenient().when(reconciliations.lockById(RECON_ID)).thenReturn(Optional.of(recon));
        lenient().when(reconciliations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reconciliations.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(calculator.compute(any())).thenReturn(balanced());
        as(PREPARER);
    }

    @AfterEach
    void clearUser() {
        SecurityContextHolder.clearContext();
    }

    private static void as(String user) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(user, null, List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, user));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static ReconciliationSnapshot balanced() {
        return snapshotOf(liveTerms("0.00", "1234.5600", "0"), List.of(), List.of(), null);
    }

    /** Terms with the difference, the live GL ending balance, the opening difference. */
    private static Terms liveTerms(String difference, String glEnding, String openingDifference) {
        Terms base = terms(difference, openingDifference);
        return new Terms(
                base.statementClosingBalance(),
                base.sumOutstandingLedgerItems(),
                base.sumOutstandingBankItems(),
                amount("1234.5600"),
                amount(glEnding),
                base.sumLateAdjustments(),
                base.adjustedBookBalance(),
                base.difference(),
                base.statementOpeningBalance(),
                base.openingLedgerItems(),
                base.openingBankItems(),
                base.glOpeningBalance(),
                base.sumOpeningAdjustments(),
                base.openingDifference());
    }

    private static ReconciliationSnapshot snapshotOf(
            Terms terms, List<BankTransaction> bank, List<LedgerLine> ledger, BankStatement baseline) {
        return new ReconciliationSnapshot(
                terms, baseline, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), bank, ledger,
                List.of(), List.of());
    }

    private static LedgerLine line() {
        return new LedgerLine(
                UUIDv7Generator.generate(),
                UUIDv7Generator.generate(),
                "JE-1",
                END,
                amount("10.00"),
                "line",
                "JE",
                false);
    }

    private void submitted(String by) {
        recon.setStatus(ReconciliationStatus.SUBMITTED);
        recon.setSubmittedBy(by);
        recon.setSubmittedAt(Instant.now(clock));
    }

    // ---- transition table (§3.8, §4.10) --------------------------------------------------------

    @Nested
    @DisplayName("transition table")
    class Transitions {

        @ParameterizedTest(name = "submit from {0}")
        @EnumSource(ReconciliationStatus.class)
        @DisplayName("submit is legal from IN_PROGRESS only")
        void submitFrom(ReconciliationStatus from) {
            recon.setStatus(from);
            if (from == ReconciliationStatus.IN_PROGRESS) {
                assertThat(service.submit(RECON_ID, null).getStatus()).isEqualTo(ReconciliationApiStatus.SUBMITTED);
            } else if (from == ReconciliationStatus.FINALIZED) {
                assertThatThrownBy(() -> service.submit(RECON_ID, null))
                        .isInstanceOf(ReconciliationAlreadyFinalizedException.class);
            } else {
                assertCode(() -> service.submit(RECON_ID, null), BankRecErrorCode.RECONCILIATION_NOT_EDITABLE);
            }
        }

        @ParameterizedTest(name = "approve from {0}")
        @EnumSource(ReconciliationStatus.class)
        @DisplayName("approve is legal from SUBMITTED only (AC 8)")
        void approveFrom(ReconciliationStatus from) {
            recon.setStatus(from);
            recon.setSubmittedBy(PREPARER);
            as(APPROVER);
            if (from == ReconciliationStatus.SUBMITTED) {
                assertThat(service.approve(RECON_ID, null).getStatus()).isEqualTo(ReconciliationApiStatus.FINALIZED);
            } else if (from == ReconciliationStatus.FINALIZED) {
                assertThatThrownBy(() -> service.approve(RECON_ID, null))
                        .isInstanceOf(ReconciliationAlreadyFinalizedException.class);
            } else {
                assertCode(() -> service.approve(RECON_ID, null), BankRecErrorCode.RECONCILIATION_NOT_SUBMITTED);
            }
        }

        @ParameterizedTest(name = "return from {0}")
        @EnumSource(ReconciliationStatus.class)
        @DisplayName("return is legal from SUBMITTED only (AC 8)")
        void returnFrom(ReconciliationStatus from) {
            recon.setStatus(from);
            recon.setSubmittedBy(PREPARER);
            ReconciliationReasonRequest request = new ReconciliationReasonRequest(WHY, null);
            if (from == ReconciliationStatus.SUBMITTED) {
                BankReconciliationResponse response = service.returnToPreparer(RECON_ID, request);
                assertThat(response.getStatus()).isEqualTo(ReconciliationApiStatus.IN_PROGRESS);
                assertThat(recon.getSubmittedBy()).isNull();
            } else {
                assertCode(
                        () -> service.returnToPreparer(RECON_ID, request),
                        BankRecErrorCode.RECONCILIATION_NOT_SUBMITTED);
            }
        }

        @ParameterizedTest(name = "cancel from {0}")
        @EnumSource(ReconciliationStatus.class)
        @DisplayName("cancel is legal from IN_PROGRESS and SUBMITTED")
        void cancelFrom(ReconciliationStatus from) {
            recon.setStatus(from);
            ReconciliationJustificationRequest request = new ReconciliationJustificationRequest(WHY, null, null);
            if (EnumSet.of(ReconciliationStatus.IN_PROGRESS, ReconciliationStatus.SUBMITTED)
                    .contains(from)) {
                assertThat(service.cancel(RECON_ID, request).getStatus()).isEqualTo(ReconciliationApiStatus.CANCELLED);
            } else if (from == ReconciliationStatus.FINALIZED) {
                assertThatThrownBy(() -> service.cancel(RECON_ID, request))
                        .isInstanceOf(ReconciliationAlreadyFinalizedException.class);
            } else {
                assertCode(() -> service.cancel(RECON_ID, request), BankRecErrorCode.RECONCILIATION_NOT_EDITABLE);
            }
        }

        @ParameterizedTest(name = "supersede from {0}")
        @EnumSource(ReconciliationStatus.class)
        @DisplayName("supersede is legal from FINALIZED and INVALIDATED")
        void supersedeFrom(ReconciliationStatus from) {
            recon.setStatus(from);
            lenient().when(reconciliations.findStatementIdById(RECON_ID)).thenReturn(Optional.of(STATEMENT_ID));
            lenient()
                    .when(statements.lockById(STATEMENT_ID))
                    .thenReturn(Optional.of(statement(STATEMENT_ID, START, END, null)));
            ReconciliationJustificationRequest request = new ReconciliationJustificationRequest(WHY, null, null);
            switch (from) {
                case FINALIZED, INVALIDATED ->
                    assertThat(service.supersede(RECON_ID, request).getSupersedesReconciliationId())
                            .isEqualTo(RECON_ID);
                case IN_PROGRESS, SUBMITTED ->
                    assertCode(
                            () -> service.supersede(RECON_ID, request),
                            BankRecErrorCode.RECONCILIATION_WINDOW_ALREADY_RECONCILED);
                default ->
                    assertCode(
                            () -> service.supersede(RECON_ID, request), BankRecErrorCode.RECONCILIATION_NOT_EDITABLE);
            }
        }

        @Test
        @DisplayName("a stale version answers 409 OPTIMISTIC_LOCK before anything changes")
        void staleVersion() {
            assertCode(
                    () -> service.submit(RECON_ID, new ReconciliationTransitionRequest(2L)),
                    BankRecErrorCode.OPTIMISTIC_LOCK);
            assertThat(recon.getStatus()).isEqualTo(ReconciliationStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("return and cancel need a justification of at least 10 characters (D15)")
        void justifications() {
            submitted(PREPARER);
            assertCode(
                    () -> service.returnToPreparer(RECON_ID, new ReconciliationReasonRequest("short", null)),
                    BankRecErrorCode.JUSTIFICATION_REQUIRED);
            assertCode(
                    () -> service.cancel(RECON_ID, new ReconciliationJustificationRequest(" ", null, null)),
                    BankRecErrorCode.VALIDATION_ERROR);
        }
    }

    // ---- E4 (§3.7, D2) -------------------------------------------------------------------------

    @Nested
    @DisplayName("gate E4")
    class Gate {

        @Test
        @DisplayName("a difference beyond 0.01 answers NOT_BALANCED first, even with unexplained lines (AC 3)")
        void notBalancedFirst() {
            when(calculator.compute(recon))
                    .thenReturn(
                            snapshotOf(liveTerms("-0.05", "0", "0"), List.of(), List.of(line(), line(), line()), null));

            assertThatThrownBy(() -> service.submit(RECON_ID, null))
                    .isInstanceOfSatisfying(
                            ReconciliationNotBalancedException.class,
                            e -> assertThat(e.getDifference()).isEqualByComparingTo("-0.05"));
            assertThat(recon.getStatus()).isEqualTo(ReconciliationStatus.IN_PROGRESS);
            verifyNoInteractions(facts);
        }

        @Test
        @DisplayName("balanced with 3 unexplained lines answers HAS_UNEXPLAINED_ITEMS with counts and ids (AC 3)")
        void unexplained() {
            List<LedgerLine> lines = List.of(line(), line(), line());
            when(calculator.compute(recon)).thenReturn(snapshotOf(liveTerms("0.00", "0", "0"), List.of(), lines, null));

            assertThatThrownBy(() -> service.submit(RECON_ID, null))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.RECONCILIATION_HAS_UNEXPLAINED_ITEMS);
                        Map<String, String> fields = e.fieldErrors();
                        assertThat(fields)
                                .containsEntry("countUnexplainedBank", "0")
                                .containsEntry("countUnexplainedLedger", "3");
                        for (int i = 0; i < 3; i++) {
                            assertThat(fields)
                                    .containsEntry(
                                            "unexplainedGlLineIds[" + i + "]",
                                            lines.get(i).lineId().toString());
                        }
                    });
        }

        @Test
        @DisplayName("the refusal lists the first 50 ids per side, never more")
        void firstFifty() {
            List<BankTransaction> bank = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                bank.add(transaction("1.00", END));
            }
            when(calculator.compute(recon)).thenReturn(snapshotOf(liveTerms("0", "0", "0"), bank, List.of(), null));

            assertThatThrownBy(() -> service.submit(RECON_ID, null))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.fieldErrors()).containsEntry("countUnexplainedBank", "60");
                        assertThat(e.fieldErrors()).containsKey("unexplainedBankTransactionIds[49]");
                        assertThat(e.fieldErrors()).doesNotContainKey("unexplainedBankTransactionIds[50]");
                    });
        }

        @Test
        @DisplayName("a non-zero opening difference never gates submit or approve (AC 4, §8.1) [M]")
        void openingDifferenceNeverGates() {
            when(calculator.compute(recon))
                    .thenReturn(snapshotOf(liveTerms("0.00", "900.00", "-250.00"), List.of(), List.of(), null));

            service.submit(RECON_ID, null);
            as(APPROVER);
            BankReconciliationResponse approved = service.approve(RECON_ID, null);

            assertThat(approved.getStatus()).isEqualTo(ReconciliationApiStatus.FINALIZED);
            assertThat(approved.getOpeningDifference()).isEqualByComparingTo("-250.00");
        }

        @Test
        @DisplayName("approve re-evaluates E4 on the live figures under the row lock (AC 9) [M]")
        void approveRecomputesLive() {
            submitted(PREPARER);
            as(APPROVER);
            when(calculator.compute(recon))
                    .thenReturn(snapshotOf(liveTerms("-40.00", "1274.56", "0"), List.of(), List.of(line()), null));

            assertThatThrownBy(() -> service.approve(RECON_ID, null))
                    .isInstanceOf(ReconciliationNotBalancedException.class);
            verify(reconciliations).lockById(RECON_ID);
            verify(reconciliations, never()).findById(RECON_ID);
            assertThat(recon.getStatus()).isEqualTo(ReconciliationStatus.SUBMITTED);
        }
    }

    // ---- submit and approve --------------------------------------------------------------------

    @Nested
    @DisplayName("submit and approve")
    class SubmitApprove {

        @Test
        @DisplayName("submit records the preparer, one RECONCILIATION_SUBMIT row and one .submitted fact (AC 1)")
        void submit() {
            service.submit(RECON_ID, new ReconciliationTransitionRequest(3L));

            assertThat(recon.getStatus()).isEqualTo(ReconciliationStatus.SUBMITTED);
            assertThat(recon.getSubmittedBy()).isEqualTo(PREPARER);
            assertThat(recon.getSubmittedAt()).isEqualTo(Instant.now(clock));
            verify(audit)
                    .record(
                            eq(BankRecAuditRecorder.BANK_RECONCILIATION),
                            eq(RECON_ID),
                            eq(BankRecAuditRecorder.RECONCILIATION_SUBMIT),
                            eq(PREPARER),
                            isNull(),
                            eq("status=IN_PROGRESS"),
                            argThat(v -> v.startsWith("status=SUBMITTED")));
            verify(facts).submitted(recon, PREPARER);
        }

        @Test
        @DisplayName("approve snapshots the live GL balance and the baseline, audits and publishes (AC 2)")
        void approve() {
            submitted(PREPARER);
            as(APPROVER);
            BankStatement baseline = statement(STATEMENT_ID, START, END, "Changed banks in August");
            when(calculator.compute(recon))
                    .thenReturn(snapshotOf(liveTerms("0.00", "4321.0000", "0"), List.of(), List.of(), baseline));

            BankReconciliationResponse response = service.approve(RECON_ID, null);

            assertThat(response.getApprovedGlEndingBalance()).isEqualByComparingTo("4321.00");
            assertThat(recon.getBaselineDate()).isEqualTo(START);
            assertThat(recon.getFinalizedBy()).isEqualTo(APPROVER);
            assertThat(recon.getFinalizedAt()).isEqualTo(Instant.now(clock));
            verify(audit)
                    .record(
                            eq(BankRecAuditRecorder.BANK_RECONCILIATION),
                            eq(RECON_ID),
                            eq(BankRecAuditRecorder.RECONCILIATION_APPROVE),
                            eq(APPROVER),
                            isNull(),
                            eq("status=SUBMITTED;submittedBy=" + PREPARER),
                            argThat(v -> v.contains("approvedBy=" + APPROVER) && v.contains("selfApproval=false")));
            verify(facts).approved(recon, APPROVER);
        }

        @Test
        @DisplayName("the submitter approving without the switch is 403 SELF_APPROVAL and audited (AC 7) [M]")
        void selfApprovalRefused() {
            submitted(PREPARER);
            when(policy.allowSelfApproval()).thenReturn(false);

            assertCode(() -> service.approve(RECON_ID, null), BankRecErrorCode.RECONCILIATION_SELF_APPROVAL);
            verify(audit)
                    .recordIndependently(
                            eq(BankRecAuditRecorder.BANK_RECONCILIATION),
                            eq(RECON_ID),
                            eq(BankRecAuditRecorder.RECONCILIATION_APPROVE),
                            eq(PREPARER),
                            isNull(),
                            any(),
                            argThat(v -> v.startsWith("outcome=REFUSED")));
            assertThat(recon.getStatus()).isEqualTo(ReconciliationStatus.SUBMITTED);
            verifyNoInteractions(facts);
        }

        @Test
        @DisplayName("another user approves without consulting the switch")
        void otherUserApproves() {
            submitted(PREPARER);
            as(APPROVER);

            service.approve(RECON_ID, null);

            verify(policy, never()).allowSelfApproval();
            verify(audit, never()).recordIndependently(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("with the switch on, the submitter approves and the row names them twice (AC 7)")
        void selfApprovalAllowed() {
            submitted(PREPARER);
            when(policy.allowSelfApproval()).thenReturn(true);

            service.approve(RECON_ID, null);

            assertThat(recon.getStatus()).isEqualTo(ReconciliationStatus.FINALIZED);
            verify(audit)
                    .record(
                            eq(BankRecAuditRecorder.BANK_RECONCILIATION),
                            eq(RECON_ID),
                            eq(BankRecAuditRecorder.RECONCILIATION_APPROVE),
                            eq(PREPARER),
                            isNull(),
                            eq("status=SUBMITTED;submittedBy=" + PREPARER),
                            argThat(v -> v.contains("approvedBy=" + PREPARER) && v.contains("selfApproval=true")));
        }

        @Test
        @DisplayName("approving a successor marks its predecessor SUPERSEDED with a .superseded fact (AC 13)")
        void approveSupersedesPredecessor() {
            BankReconciliation predecessor = reconciliation();
            UUID predecessorId = UUIDv7Generator.generate();
            predecessor.setReconciliationId(predecessorId);
            predecessor.setStatus(ReconciliationStatus.INVALIDATED);
            submitted(PREPARER);
            recon.setSupersedesReconciliationId(predecessorId);
            when(reconciliations.findById(predecessorId)).thenReturn(Optional.of(predecessor));
            as(APPROVER);

            service.approve(RECON_ID, null);

            assertThat(predecessor.getStatus()).isEqualTo(ReconciliationStatus.SUPERSEDED);
            assertThat(predecessor.getSupersededByReconciliationId()).isEqualTo(RECON_ID);
            verify(facts).superseded(predecessor, RECON_ID, APPROVER);
        }

        @Test
        @DisplayName(
                "approving the reconciliation of a corrected statement supersedes the old statement's (§4.9 path 3)")
        void approveSupersedesTheOldStatementsReconciliation() {
            UUID oldStatement = UUIDv7Generator.generate();
            BankStatement old = statement(oldStatement, START, END, null);
            BankReconciliation invalidated = reconciliation();
            invalidated.setReconciliationId(UUIDv7Generator.generate());
            invalidated.setStatementId(oldStatement);
            invalidated.setStatus(ReconciliationStatus.INVALIDATED);
            submitted(PREPARER);
            when(statements.findBySupersededByStatementId(STATEMENT_ID)).thenReturn(List.of(old));
            when(reconciliations.findByStatementIdInAndStatusIn(anyCollection(), anyCollection()))
                    .thenReturn(List.of(invalidated));
            as(APPROVER);

            service.approve(RECON_ID, null);

            assertThat(recon.getSupersedesReconciliationId()).isEqualTo(invalidated.getReconciliationId());
            assertThat(invalidated.getStatus()).isEqualTo(ReconciliationStatus.SUPERSEDED);
        }
    }

    // ---- cancel and supersede ------------------------------------------------------------------

    @Nested
    @DisplayName("cancel and supersede")
    class CancelSupersede {

        @Test
        @DisplayName("cancel unmatches live matches, releases registered OPEN items, publishes .cancelled (AC 14)")
        void cancel() {
            submitted(PREPARER);
            BankReconciliationMatch proposed = match(MatchState.PROPOSED);
            BankReconciliationMatch accepted = match(MatchState.ACCEPTED);
            BankReconciliationMatch rejected = match(MatchState.REJECTED);
            when(matches.findByReconciliationIdOrderByCreatedAtAsc(RECON_ID))
                    .thenReturn(List.of(proposed, accepted, rejected));
            BankReconciliationOutstandingItem item = new BankReconciliationOutstandingItem();
            item.setStatus(OutstandingItemStatus.OPEN);
            item.setSide(OutstandingItemSide.LEDGER);
            item.setItemKind(OutstandingItemKind.DEPOSIT_IN_TRANSIT);
            when(items.findByRegisteredInReconciliationIdAndStatus(RECON_ID, OutstandingItemStatus.OPEN))
                    .thenReturn(List.of(item));
            as(APPROVER);

            service.cancel(RECON_ID, new ReconciliationJustificationRequest(WHY, null, null));

            verify(writer).end(proposed, MatchState.UNMATCHED, "RECONCILIATION_CANCELLED", APPROVER);
            verify(writer).end(accepted, MatchState.UNMATCHED, "RECONCILIATION_CANCELLED", APPROVER);
            verify(writer, never()).end(eq(rejected), any(), any(), any());
            assertThat(item.getStatus()).isEqualTo(OutstandingItemStatus.RELEASED);
            assertThat(item.getReleaseReason()).isEqualTo("RECONCILIATION_CANCELLED");
            assertThat(recon.getCancelReason()).isEqualTo(WHY);
            assertThat(recon.getCancelledBy()).isEqualTo(APPROVER);
            verify(facts).cancelled(recon, WHY, APPROVER);
        }

        @Test
        @DisplayName("supersede releases the predecessor's members and re-proposes its accepted matches (AC 13)")
        void supersede() {
            recon.setStatus(ReconciliationStatus.INVALIDATED);
            when(reconciliations.findStatementIdById(RECON_ID)).thenReturn(Optional.of(STATEMENT_ID));
            when(statements.lockById(STATEMENT_ID)).thenReturn(Optional.of(statement(STATEMENT_ID, START, END, null)));
            BankReconciliationMatch accepted = match(MatchState.ACCEPTED);
            BankReconciliationMatch broken = match(MatchState.BROKEN);
            when(matches.findByReconciliationIdOrderByCreatedAtAsc(RECON_ID)).thenReturn(List.of(accepted, broken));
            BankTransaction row = transaction("25.00", END);
            row.setStatus(com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus.MATCHED);
            BankReconciliationBankMatch bankMember =
                    new BankReconciliationBankMatch(accepted.getMatchId(), row.getBankTransactionId());
            BankReconciliationGlMatch glMember = new BankReconciliationGlMatch();
            glMember.setMatchId(accepted.getMatchId());
            glMember.setGlLineId(UUIDv7Generator.generate());
            glMember.setSignedAmount(new BigDecimal("25.00"));
            glMember.setActive(true);
            when(bankMatches.findByMatchIdAndActiveTrue(accepted.getMatchId())).thenReturn(List.of(bankMember));
            when(glMatches.findByMatchIdAndActiveTrue(accepted.getMatchId())).thenReturn(List.of(glMember));
            when(transactions.findAllById(List.of(row.getBankTransactionId()))).thenReturn(List.of(row));
            UUID requestId = UUIDv7Generator.generate();
            as(APPROVER);

            BankReconciliationResponse successor =
                    service.supersede(RECON_ID, new ReconciliationJustificationRequest(WHY, requestId, null));

            assertThat(successor.getStatus()).isEqualTo(ReconciliationApiStatus.IN_PROGRESS);
            assertThat(successor.getSupersedesReconciliationId()).isEqualTo(RECON_ID);
            assertThat(bankMember.isActive()).isFalse();
            assertThat(glMember.isActive()).isFalse();
            assertThat(row.getStatus())
                    .isEqualTo(com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus.UNMATCHED);
            assertThat(accepted.getState()).isEqualTo(MatchState.ACCEPTED);
            verify(writer)
                    .create(
                            any(),
                            argThat((Header h) -> h.state() == MatchState.PROPOSED && h.kind() == MatchKind.ONE_TO_ONE),
                            eq(List.of(row)),
                            argThat(members -> members.size() == 1
                                    && members.getFirst().glLineId().equals(glMember.getGlLineId())),
                            eq(APPROVER));
            verify(audit)
                    .record(
                            eq(BankRecAuditRecorder.BANK_RECONCILIATION),
                            eq(RECON_ID),
                            eq(BankRecAuditRecorder.RECONCILIATION_SUPERSEDE),
                            eq(APPROVER),
                            eq(WHY),
                            eq("status=INVALIDATED"),
                            argThat(v -> v.startsWith("successorReconciliationId=")));
        }

        @Test
        @DisplayName("supersede re-opens the items the released matches cleared, until the successor re-accepts")
        void supersedeReopensClearedItems() {
            recon.setStatus(ReconciliationStatus.INVALIDATED);
            when(reconciliations.findStatementIdById(RECON_ID)).thenReturn(Optional.of(STATEMENT_ID));
            when(statements.lockById(STATEMENT_ID)).thenReturn(Optional.of(statement(STATEMENT_ID, START, END, null)));
            BankReconciliationMatch accepted = match(MatchState.ACCEPTED);
            when(matches.findByReconciliationIdOrderByCreatedAtAsc(RECON_ID)).thenReturn(List.of(accepted));
            BankReconciliationGlMatch glMember = new BankReconciliationGlMatch();
            glMember.setMatchId(accepted.getMatchId());
            glMember.setGlLineId(UUIDv7Generator.generate());
            glMember.setSignedAmount(new BigDecimal("25.00"));
            glMember.setActive(true);
            when(glMatches.findByMatchIdAndActiveTrue(accepted.getMatchId())).thenReturn(List.of(glMember));
            BankReconciliationOutstandingItem cleared = new BankReconciliationOutstandingItem();
            cleared.setSide(OutstandingItemSide.LEDGER);
            cleared.setGlLineId(glMember.getGlLineId());
            cleared.setStatus(OutstandingItemStatus.CLEARED);
            cleared.setClearedInReconciliationId(RECON_ID);
            cleared.setClearedByMatchId(accepted.getMatchId());
            cleared.setClearedAt(Instant.now(clock));
            cleared.setClearedBy(PREPARER);
            cleared.setClosedOn(END);
            when(items.findByClearedByMatchIdAndStatus(accepted.getMatchId(), OutstandingItemStatus.CLEARED))
                    .thenReturn(List.of(cleared));
            as(APPROVER);

            service.supersede(RECON_ID, new ReconciliationJustificationRequest(WHY, null, null));

            assertThat(cleared.getStatus()).isEqualTo(OutstandingItemStatus.OPEN);
            assertThat(cleared.getClearedInReconciliationId()).isNull();
            assertThat(cleared.getClearedByMatchId()).isNull();
            assertThat(cleared.getClearedAt()).isNull();
            assertThat(cleared.getClearedBy()).isNull();
            assertThat(cleared.getClosedOn()).isNull();
            verify(items).saveAll(List.of(cleared));
        }

        @Test
        @DisplayName("a supersede replay returns the successor it created")
        void supersedeReplay() {
            BankReconciliation successor = reconciliation();
            successor.setReconciliationId(UUIDv7Generator.generate());
            successor.setSupersedesReconciliationId(RECON_ID);
            successor.setRequestHash(ReconciliationApprovalServiceImpl.supersedeHash(RECON_ID, WHY, 3L));
            UUID requestId = UUIDv7Generator.generate();
            when(reconciliations.findByRequestId(requestId)).thenReturn(Optional.of(successor));

            BankReconciliationResponse replay = service.supersede(
                    RECON_ID, new ReconciliationJustificationRequest("  " + WHY + " ", requestId, 3L));

            assertThat(replay.isReplayed()).isTrue();
            assertThat(replay.getReconciliationId()).isEqualTo(successor.getReconciliationId());
        }

        @Test
        @DisplayName("a supersede requestId reused with another justification or version is IDEMPOTENCY_CONFLICT")
        void supersedeReplayWithAnotherPayload() {
            BankReconciliation successor = reconciliation();
            successor.setReconciliationId(UUIDv7Generator.generate());
            successor.setSupersedesReconciliationId(RECON_ID);
            successor.setRequestHash(ReconciliationApprovalServiceImpl.supersedeHash(RECON_ID, WHY, 3L));
            UUID requestId = UUIDv7Generator.generate();
            when(reconciliations.findByRequestId(requestId)).thenReturn(Optional.of(successor));

            assertCode(
                    () -> service.supersede(
                            RECON_ID,
                            new ReconciliationJustificationRequest("A different reason entirely", requestId, 3L)),
                    BankRecErrorCode.IDEMPOTENCY_CONFLICT);
            assertCode(
                    () -> service.supersede(RECON_ID, new ReconciliationJustificationRequest(WHY, requestId, 4L)),
                    BankRecErrorCode.IDEMPOTENCY_CONFLICT);
            verify(reconciliations, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("supersede stores the command's hash with its requestId")
        void supersedeStoresRequestHash() {
            recon.setStatus(ReconciliationStatus.INVALIDATED);
            when(reconciliations.findStatementIdById(RECON_ID)).thenReturn(Optional.of(STATEMENT_ID));
            when(statements.lockById(STATEMENT_ID)).thenReturn(Optional.of(statement(STATEMENT_ID, START, END, null)));
            UUID requestId = UUIDv7Generator.generate();
            as(APPROVER);

            service.supersede(RECON_ID, new ReconciliationJustificationRequest(WHY, requestId, 3L));

            verify(reconciliations)
                    .saveAndFlush(argThat((BankReconciliation r) -> requestId.equals(r.getRequestId())
                            && ReconciliationApprovalServiceImpl.supersedeHash(RECON_ID, WHY, 3L)
                                    .equals(r.getRequestHash())));
        }

        private BankReconciliationMatch match(MatchState state) {
            BankReconciliationMatch match = new BankReconciliationMatch();
            match.setMatchId(UUIDv7Generator.generate());
            match.setReconciliationId(RECON_ID);
            match.setMatchKind(MatchKind.ONE_TO_ONE);
            match.setOrigin(MatchOrigin.USER);
            match.setState(state);
            return match;
        }
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, BankRecErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BankRecException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
