package com.positivity.accounting.internal.bankrec.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.FeedChange;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import com.positivity.accounting.internal.bankrec.service.BankRecAuditRecorder;
import com.positivity.accounting.internal.bankrec.service.BankStatementFacts;
import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.domainevents.accounting.BankStatementCommittedV1;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.BankTransactionObserved;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.Change;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.SettlementState;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.StatementHeader;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
 * Unit tests of the intake port (SPEC-manual-bank-reconciliation §3.1, §3.2, §4.2–§4.5, §8.1–§8.2;
 * story S2, #2301): the check order, E1/E2, the acknowledgement rules, U3, R1, the profile and the
 * baseline.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankTransactionIntakeImpl")
class BankTransactionIntakeImplTest {

    private static final UUID ACCOUNT = UUID.fromString("5eed0acc-0000-4000-8000-000000001000");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-31T12:00:00Z"), ZoneOffset.UTC);
    private static final Instant OBSERVED = Instant.parse("2026-12-31T10:00:00Z");
    private static final String ACTOR = "preparer";
    private static final String ACK = "switched banks in August";

    @Mock
    private BankCashAccounts bankCashAccounts;

    @Mock
    private BankStatementRepository statements;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private BankAccountProfileRepository profiles;

    @Mock
    private BankReconciliationRepository reconciliations;

    @Mock
    private BankRecAuditRecorder audit;

    @Mock
    private BankStatementFacts facts;

    private BankTransactionIntakeImpl intake;
    private final List<BankTransaction> saved = new ArrayList<>();
    private final List<BankStatement> savedStatements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        intake = new BankTransactionIntakeImpl(
                bankCashAccounts,
                new FunctionalCurrency("USD"),
                statements,
                transactions,
                profiles,
                reconciliations,
                audit,
                facts,
                CLOCK);
        lenient()
                .when(bankCashAccounts.requireForIntake(ACCOUNT))
                .thenReturn(new BankCashAccount(ACCOUNT, "1000", "Cash"));
        lenient().when(statements.saveAndFlush(any(BankStatement.class))).thenAnswer(inv -> {
            BankStatement s = inv.getArgument(0);
            s.setStatementId(UUID.fromString("01990000-0000-7000-8000-00000000000" + savedStatements.size()));
            savedStatements.add(s);
            return s;
        });
        lenient().when(transactions.save(any(BankTransaction.class))).thenAnswer(inv -> {
            BankTransaction t = inv.getArgument(0);
            if (t.getBankTransactionId() == null) {
                t.setBankTransactionId(
                        UUID.fromString(String.format("01990000-0000-7000-8000-%012d", 100 + saved.size())));
                saved.add(t);
            }
            return t;
        });
        lenient().when(profiles.save(any(BankAccountProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        // The latest acknowledged statement is, by default, the one this call commits.
        lenient()
                .when(statements.findFirstByGlAccountIdAndStatusAndGapAcknowledgementIsNotNullOrderByStartDateDesc(
                        ACCOUNT, BankStatementStatus.COMMITTED))
                .thenAnswer(inv -> savedStatements.stream()
                        .filter(s -> s.getGapAcknowledgement() != null)
                        .reduce((a, b) -> b));
    }

    // ---- fixtures -------------------------------------------------------------------------------

    static BankTransactionObserved row(LocalDate date, String amount, String description) {
        return row(null, date, amount, description, null);
    }

    static BankTransactionObserved row(
            String sourceId, LocalDate date, String amount, String description, String reference) {
        return new BankTransactionObserved(
                sourceId,
                1,
                sourceId == null ? Change.ADDED : Change.ADDED,
                SettlementState.POSTED,
                date,
                null,
                new BigDecimal(amount),
                null,
                description,
                null,
                reference,
                null,
                null,
                null,
                null);
    }

    static BankTransactionsObservedV1 manual(StatementHeader header, List<BankTransactionObserved> rows) {
        return new BankTransactionsObservedV1(
                BankTransactionsObservedV1.SourceKind.MANUAL_ENTRY,
                null,
                null,
                null,
                null,
                "USD",
                OBSERVED,
                null,
                header,
                rows);
    }

    static StatementHeader header(String start, String end, String opening, String closing) {
        return new StatementHeader(
                null, LocalDate.parse(start), LocalDate.parse(end), new BigDecimal(opening), new BigDecimal(closing));
    }

    static IntakeContext ctx(String acknowledgement) {
        return IntakeContext.of(ACCOUNT, ACTOR, acknowledgement, null);
    }

    private void previousStatement(String start, String end, String closing) {
        BankStatement previous = new BankStatement();
        previous.setStatementId(UUID.fromString("01980000-0000-7000-8000-000000000001"));
        previous.setGlAccountId(ACCOUNT);
        previous.setStartDate(LocalDate.parse(start));
        previous.setEndDate(LocalDate.parse(end));
        previous.setClosingBalance(new BigDecimal(closing));
        previous.setStatus(BankStatementStatus.COMMITTED);
        // Lenient: the acknowledgement's shape is checked before contiguity, so a shape refusal never reads it.
        lenient()
                .when(statements.findFirstByGlAccountIdAndStatusAndEndDateLessThanOrderByEndDateDesc(
                        eq(ACCOUNT), eq(BankStatementStatus.COMMITTED), any()))
                .thenReturn(Optional.of(previous));
    }

    private void existingProfile(LocalDate baseline) {
        BankAccountProfile profile = new BankAccountProfile(ACCOUNT);
        profile.setCurrency("USD");
        profile.setReconciliationBaselineDate(baseline);
        when(profiles.findById(ACCOUNT)).thenReturn(Optional.of(profile));
    }

    private static BankRecErrorCode codeOf(Throwable thrown) {
        return ((BankRecException) thrown).code();
    }

    private void assertNothingWritten() {
        verify(statements, never()).saveAndFlush(any());
        verify(transactions, never()).save(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    // ---- account and currency -------------------------------------------------------------------

    @Test
    void anAccountThatIsNotABankAccountIsRefusedFirst() {
        when(bankCashAccounts.requireForIntake(ACCOUNT))
                .thenThrow(new BankRecException(BankRecErrorCode.ACCOUNT_NOT_RECONCILABLE, "no"));
        assertThatThrownBy(() -> intake.accept(
                        manual(
                                header("2026-09-01", "2026-09-30", "0", "10"),
                                List.of(row(LocalDate.of(2026, 9, 2), "10", "DEP"))),
                        ctx(ACK)))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.ACCOUNT_NOT_RECONCILABLE));
        assertNothingWritten();
    }

    @Test
    void aCurrencyOtherThanTheLedgerCurrencyIsRefusedWhileNoProfileExists() {
        when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());
        BankTransactionsObservedV1 euro = new BankTransactionsObservedV1(
                BankTransactionsObservedV1.SourceKind.MANUAL_ENTRY,
                null,
                null,
                null,
                null,
                "EUR",
                OBSERVED,
                null,
                header("2026-09-01", "2026-09-30", "0", "10"),
                List.of(row(LocalDate.of(2026, 9, 2), "10", "DEP")));
        assertThatThrownBy(() -> intake.accept(euro, ctx(ACK)))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.CURRENCY_NOT_SUPPORTED));
        assertNothingWritten();
    }

    // ---- first statement, profile and baseline --------------------------------------------------

    @Nested
    class FirstStatement {

        @Test
        void withoutAnAcknowledgementItIsNotContiguous() {
            when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> intake.accept(
                            manual(
                                    header("2026-09-01", "2026-09-30", "0", "10"),
                                    List.of(row(LocalDate.of(2026, 9, 2), "10", "DEP"))),
                            ctx(null)))
                    .satisfies(t -> {
                        assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.STATEMENT_NOT_CONTIGUOUS);
                        assertThat(((BankRecException) t).fieldErrors()).containsKey("gapAcknowledgement");
                    });
            assertNothingWritten();
        }

        @Test
        void withAnAcknowledgementItCommitsCreatesTheProfileAndSetsTheBaselineOnce() {
            when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());

            IntakeResult result = intake.accept(
                    manual(
                            header("2026-09-01", "2026-09-30", "100.00", "110.00"),
                            List.of(row(LocalDate.of(2026, 9, 2), "10", "DEP"))),
                    ctx(ACK));

            BankStatement statement = savedStatements.getFirst();
            assertThat(statement.getGapAcknowledgement()).isEqualTo(ACK);
            assertThat(statement.getGapAcknowledgedBy()).isEqualTo(ACTOR);
            assertThat(statement.getGapAcknowledgedAt()).isEqualTo(Instant.now(CLOCK));
            assertThat(statement.getActivityTotal()).isEqualByComparingTo("10");
            assertThat(statement.getSourceKind()).isEqualTo(SourceKind.MANUAL_ENTRY);

            ArgumentCaptor<BankAccountProfile> profile = ArgumentCaptor.forClass(BankAccountProfile.class);
            verify(profiles, times(2)).save(profile.capture());
            BankAccountProfile created = profile.getAllValues().getFirst();
            assertThat(created.getBankName()).isNull();
            assertThat(created.getAccountMask()).isNull();
            assertThat(created.getCurrency()).isEqualTo("USD");
            assertThat(created.getDefaultColumnMapping()).isNull();
            assertThat(created.getReconciliationBaselineDate()).isEqualTo(LocalDate.of(2026, 9, 1));

            verify(audit, times(1))
                    .record(
                            BankRecAuditRecorder.BANK_ACCOUNT_PROFILE,
                            ACCOUNT,
                            BankRecAuditRecorder.BANK_ACCOUNT_BASELINE_SET,
                            ACTOR,
                            ACK,
                            null,
                            "2026-09-01");
            assertThat(result.baselineChanged()).isTrue();
            assertThat(result.reconciliationBaselineDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(result.bankTransactionCount()).isEqualTo(1);

            ArgumentCaptor<BankStatementCommittedV1> fact = ArgumentCaptor.forClass(BankStatementCommittedV1.class);
            verify(facts).committed(fact.capture(), eq(ACTOR));
            assertThat(fact.getValue().transactionCount()).isEqualTo(1);
            assertThat(fact.getValue().currency()).isEqualTo("USD");
        }
    }

    // ---- contiguity and the acknowledgement order (§4.2) ----------------------------------------

    @Nested
    class Contiguity {

        @BeforeEach
        void previous() {
            previousStatement("2026-09-01", "2026-09-30", "12345.67");
        }

        @Test
        void aContiguousStatementLeavesTheBaselineAndWritesNoAuditRow() {
            existingProfile(LocalDate.of(2026, 9, 1));
            IntakeResult result = intake.accept(
                    manual(
                            header("2026-10-01", "2026-10-31", "12345.67", "12355.67"),
                            List.of(row(LocalDate.of(2026, 10, 2), "10", "DEP"))),
                    ctx(null));
            assertThat(result.baselineChanged()).isFalse();
            assertThat(result.reconciliationBaselineDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
            assertThat(savedStatements.getFirst().getGapAcknowledgement()).isNull();
        }

        @Test
        void aValidAcknowledgementOnAContiguousStatementIsRefused() {
            existingProfile(LocalDate.of(2026, 9, 1));
            assertThatThrownBy(() -> intake.accept(
                            manual(
                                    header("2026-10-01", "2026-10-31", "12345.67", "12355.67"),
                                    List.of(row(LocalDate.of(2026, 10, 2), "10", "DEP"))),
                            ctx(ACK)))
                    .satisfies(t -> assertThat(codeOf(t))
                            .isEqualTo(BankRecErrorCode.STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE));
            assertNothingWritten();
        }

        @Test
        void aShortAcknowledgementIsJustificationRequiredWhateverTheContiguity() {
            existingProfile(LocalDate.of(2026, 9, 1));
            // Contiguous …
            assertThatThrownBy(() -> intake.accept(
                            manual(
                                    header("2026-10-01", "2026-10-31", "12345.67", "12355.67"),
                                    List.of(row(LocalDate.of(2026, 10, 2), "10", "DEP"))),
                            ctx("short")))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));
            // … and not: one condition, one code (§4.2 step 1).
            assertThatThrownBy(() -> intake.accept(
                            manual(
                                    header("2026-10-01", "2026-10-31", "12300.00", "12310.00"),
                                    List.of(row(LocalDate.of(2026, 10, 2), "10", "DEP"))),
                            ctx("short")))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));
        }

        @Test
        void aBlankAcknowledgementIsAValidationError() {
            existingProfile(LocalDate.of(2026, 9, 1));
            assertThatThrownBy(() -> intake.accept(
                            manual(
                                    header("2026-10-01", "2026-10-31", "12300.00", "12310.00"),
                                    List.of(row(LocalDate.of(2026, 10, 2), "10", "DEP"))),
                            ctx("   ")))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.VALIDATION_ERROR));
        }

        @Test
        void anOpeningBalanceThatDiffersNamesTheExpectedValue() {
            existingProfile(LocalDate.of(2026, 9, 1));
            assertThatThrownBy(() -> intake.accept(
                            manual(
                                    header("2026-10-01", "2026-10-31", "12300.00", "12310.00"),
                                    List.of(row(LocalDate.of(2026, 10, 2), "10", "DEP"))),
                            ctx(null)))
                    .satisfies(t -> {
                        assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.STATEMENT_NOT_CONTIGUOUS);
                        assertThat(((BankRecException) t).fieldErrors())
                                .containsEntry("openingBalance", "expected 12345.67")
                                .doesNotContainKey("startDate");
                    });
        }

        @Test
        void aDateGapNeedsAnAcknowledgementAndOneMovesTheBaselineWithOneAuditRow() {
            existingProfile(LocalDate.of(2026, 9, 1));
            assertThatThrownBy(() -> intake.accept(
                            manual(
                                    header("2026-12-01", "2026-12-31", "12345.67", "12355.67"),
                                    List.of(row(LocalDate.of(2026, 12, 2), "10", "DEP"))),
                            ctx(null)))
                    .satisfies(t -> assertThat(((BankRecException) t).fieldErrors())
                            .containsEntry("startDate", "expected 2026-10-01"));

            IntakeResult result = intake.accept(
                    manual(
                            header("2026-12-01", "2026-12-31", "12345.67", "12355.67"),
                            List.of(row(LocalDate.of(2026, 12, 2), "10", "DEP"))),
                    ctx(ACK));
            assertThat(result.reconciliationBaselineDate()).isEqualTo(LocalDate.of(2026, 12, 1));
            verify(audit, times(1))
                    .record(
                            BankRecAuditRecorder.BANK_ACCOUNT_PROFILE,
                            ACCOUNT,
                            BankRecAuditRecorder.BANK_ACCOUNT_BASELINE_SET,
                            ACTOR,
                            ACK,
                            "2026-09-01",
                            "2026-12-01");
        }
    }

    // ---- U1, U2, window, E1 ---------------------------------------------------------------------

    @Test
    void theSameWindowIsAlreadyImported() {
        BankStatement same = new BankStatement();
        same.setStatementId(UUID.fromString("01980000-0000-7000-8000-000000000009"));
        when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());
        when(statements.findFirstByGlAccountIdAndStatusAndStartDateAndEndDate(
                        ACCOUNT, BankStatementStatus.COMMITTED, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(Optional.of(same));
        assertThatThrownBy(() -> intake.accept(
                        manual(
                                header("2026-09-01", "2026-09-30", "0", "10"),
                                List.of(row(LocalDate.of(2026, 9, 2), "10", "DEP"))),
                        ctx(ACK)))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.STATEMENT_ALREADY_IMPORTED));
    }

    @Test
    void anOverlappingWindowNamesTheStatement() {
        BankStatement overlapping = new BankStatement();
        overlapping.setStatementId(UUID.fromString("01980000-0000-7000-8000-000000000009"));
        when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());
        when(statements
                        .findFirstByGlAccountIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAsc(
                                ACCOUNT,
                                BankStatementStatus.COMMITTED,
                                LocalDate.of(2026, 10, 14),
                                LocalDate.of(2026, 9, 15)))
                .thenReturn(Optional.of(overlapping));
        assertThatThrownBy(() -> intake.accept(
                        manual(
                                header("2026-09-15", "2026-10-14", "0", "10"),
                                List.of(row(LocalDate.of(2026, 9, 20), "10", "DEP"))),
                        ctx(ACK)))
                .satisfies(t -> {
                    assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.STATEMENT_PERIOD_OVERLAP);
                    assertThat(((BankRecException) t).fieldErrors())
                            .containsEntry("statementId", "01980000-0000-7000-8000-000000000009");
                });
    }

    @Test
    void aManualTransactionOutsideTheWindowIsRefusedByIndex() {
        when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> intake.accept(
                        manual(
                                header("2026-10-01", "2026-10-31", "0", "10"),
                                List.of(
                                        row(LocalDate.of(2026, 10, 2), "15", "DEP"),
                                        row(LocalDate.of(2026, 9, 28), "-5", "FEE"))),
                        ctx(ACK)))
                .satisfies(t -> {
                    assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.STATEMENT_TRANSACTION_OUT_OF_WINDOW);
                    assertThat(((BankRecException) t).fieldErrors()).containsOnlyKeys("transactions[1]");
                });
        assertNothingWritten();
    }

    @Test
    void anEndDateInTheFutureIsAValidationError() {
        when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> intake.accept(
                        manual(
                                header("2027-01-01", "2027-01-31", "0", "10"),
                                List.of(row(LocalDate.of(2027, 1, 2), "10", "DEP"))),
                        ctx(ACK)))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.VALIDATION_ERROR));
    }

    @Nested
    class ActivityE1 {

        @Test
        void aDifferenceOfOneCentPasses() {
            when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());
            intake.accept(
                    manual(
                            header("2026-09-01", "2026-09-30", "10000.00", "10000.01"),
                            List.of(
                                    row(LocalDate.of(2026, 9, 2), "10", "DEP"),
                                    row(LocalDate.of(2026, 9, 3), "-10", "FEE"))),
                    ctx(ACK));
            assertThat(savedStatements).hasSize(1);
        }

        @Test
        void aDifferenceOfTwoCentsFailsWithTheTermsInTheFieldError() {
            when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> intake.accept(
                            manual(
                                    header("2026-09-01", "2026-09-30", "10000.00", "10000.02"),
                                    List.of(
                                            row(LocalDate.of(2026, 9, 2), "10", "DEP"),
                                            row(LocalDate.of(2026, 9, 3), "-10", "FEE"))),
                            ctx(ACK)))
                    .satisfies(t -> {
                        assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.STATEMENT_ACTIVITY_MISMATCH);
                        assertThat(((BankRecException) t).fieldErrors())
                                .containsEntry("activityTotal", "opening + activity = 10000.00, closing = 10000.02");
                    });
            assertNothingWritten();
        }

        @Test
        void theSpecExampleFifteenShortIsRefused() {
            when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> intake.accept(
                            manual(
                                    header("2026-09-01", "2026-09-30", "10000.00", "10000.00"),
                                    List.of(row(LocalDate.of(2026, 9, 2), "-15.00", "FEE"))),
                            ctx(ACK)))
                    .satisfies(t -> assertThat(((BankRecException) t).fieldErrors())
                            .containsEntry("activityTotal", "opening + activity = 9985.00, closing = 10000.00"));
        }
    }

    // ---- transactions: U3 and R1 ----------------------------------------------------------------

    @Nested
    class Transactions {

        private BankTransactionsObservedV1 feed(List<BankTransactionObserved> rows) {
            return new BankTransactionsObservedV1(
                    BankTransactionsObservedV1.SourceKind.FILE_IMPORT,
                    "csv-v1",
                    null,
                    null,
                    null,
                    "USD",
                    OBSERVED,
                    null,
                    null,
                    rows);
        }

        @Test
        void theSameSourceIdIsAnUpsertNeverASecondRow() {
            existingProfile(LocalDate.of(2026, 9, 1));
            UUID importId = UUID.fromString("01980000-0000-7000-8000-0000000000aa");
            BankTransaction existing = new BankTransaction();
            existing.setBankTransactionId(UUID.fromString("01980000-0000-7000-8000-0000000000bb"));
            existing.setGlAccountId(ACCOUNT);
            existing.setDescription("ACH DEPOSIT");
            existing.setFeedChange(FeedChange.ADDED);
            existing.setLastObservedAt(Instant.parse("2026-12-01T00:00:00Z"));
            existing.setStatus(BankTransactionStatus.UNMATCHED);
            when(transactions.findFirstByGlAccountIdAndSourceKindAndSourceRefAndSourceTransactionId(
                            ACCOUNT, SourceKind.FILE_IMPORT, importId, "T1"))
                    .thenReturn(Optional.of(existing));

            IntakeResult result = intake.accept(
                    feed(List.of(row("T1", LocalDate.of(2026, 12, 2), "10", "ACH DEPOSIT CORRECTED", null))),
                    IntakeContext.of(ACCOUNT, ACTOR, null, importId));

            assertThat(saved).isEmpty();
            assertThat(existing.getFeedChange()).isEqualTo(FeedChange.MODIFIED);
            assertThat(existing.getLastObservedAt()).isEqualTo(OBSERVED);
            assertThat(existing.getDescription()).isEqualTo("ACH DEPOSIT CORRECTED");
            assertThat(existing.getOriginalDescription()).isEqualTo("ACH DEPOSIT");
            assertThat(result.modifiedCount()).isEqualTo(1);
            assertThat(result.bankTransactionIds()).containsExactly(existing.getBankTransactionId());
        }

        @Test
        void twoIdenticalRowsOfOneBatchAreBothPossibleDuplicatesAndTheSecondPointsAtTheFirst() {
            existingProfile(LocalDate.of(2026, 9, 1));
            IntakeResult result = intake.accept(
                    feed(List.of(
                            row(null, LocalDate.of(2026, 12, 2), "-5.00", "Monthly fee", "F1"),
                            row(null, LocalDate.of(2026, 12, 2), "-5", "MONTHLY FEE.", "F1"))),
                    IntakeContext.of(ACCOUNT, ACTOR, null, UUID.fromString("01980000-0000-7000-8000-0000000000aa")));

            assertThat(saved).hasSize(2);
            assertThat(saved).allMatch(t -> t.getStatus() == BankTransactionStatus.POSSIBLE_DUPLICATE);
            assertThat(saved.get(0).getDuplicateOfBankTransactionId()).isNull();
            assertThat(saved.get(1).getDuplicateOfBankTransactionId())
                    .isEqualTo(saved.get(0).getBankTransactionId());
            assertThat(result.possibleDuplicateCount()).isEqualTo(2);
        }

        @Test
        void aCollisionWithAnExistingRowFlagsOnlyTheNewRowAndIgnoresExcludedAndRemovedRows() {
            existingProfile(LocalDate.of(2026, 9, 1));
            BankTransaction original = new BankTransaction();
            original.setBankTransactionId(UUID.fromString("01980000-0000-7000-8000-0000000000cc"));
            original.setSourceKind(SourceKind.MANUAL_ENTRY);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<BankTransactionStatus>> excluded = ArgumentCaptor.forClass(Collection.class);
            when(transactions
                            .findByGlAccountIdAndFingerprintAndStatusNotInOrderByFirstObservedAtAscBankTransactionIdAsc(
                                    eq(ACCOUNT), anyString(), excluded.capture()))
                    .thenReturn(List.of(original));

            intake.accept(
                    feed(List.of(row(null, LocalDate.of(2026, 12, 2), "-5.00", "Monthly fee", "F1"))),
                    IntakeContext.of(ACCOUNT, ACTOR, null, UUID.fromString("01980000-0000-7000-8000-0000000000aa")));

            assertThat(saved.getFirst().getStatus()).isEqualTo(BankTransactionStatus.POSSIBLE_DUPLICATE);
            assertThat(saved.getFirst().getDuplicateOfBankTransactionId()).isEqualTo(original.getBankTransactionId());
            assertThat(excluded.getValue())
                    .containsExactlyInAnyOrder(BankTransactionStatus.EXCLUDED, BankTransactionStatus.REMOVED_BY_SOURCE);
        }

        @Test
        void rowsTheSameSourceReportedUnderDifferentIdsDoNotCollide() {
            existingProfile(LocalDate.of(2026, 9, 1));
            UUID importId = UUID.fromString("01980000-0000-7000-8000-0000000000aa");
            BankTransaction sibling = new BankTransaction();
            sibling.setBankTransactionId(UUID.fromString("01980000-0000-7000-8000-0000000000dd"));
            sibling.setSourceKind(SourceKind.FILE_IMPORT);
            sibling.setSourceRef(importId);
            sibling.setSourceTransactionId("T1");
            when(transactions
                            .findByGlAccountIdAndFingerprintAndStatusNotInOrderByFirstObservedAtAscBankTransactionIdAsc(
                                    eq(ACCOUNT), anyString(), any()))
                    .thenReturn(List.of(sibling));

            intake.accept(
                    feed(List.of(row("T2", LocalDate.of(2026, 12, 2), "-5.00", "Monthly fee", "F1"))),
                    IntakeContext.of(ACCOUNT, ACTOR, null, importId));

            assertThat(saved.getFirst().getStatus()).isEqualTo(BankTransactionStatus.UNMATCHED);
        }

        @Test
        void anAmountIsStoredAtFourPlacesExactlyAsDelivered() {
            existingProfile(LocalDate.of(2026, 9, 1));
            intake.accept(
                    feed(List.of(row(null, LocalDate.of(2026, 12, 2), "1234.5", "Deposit", null))),
                    IntakeContext.of(ACCOUNT, ACTOR, null, UUID.fromString("01980000-0000-7000-8000-0000000000aa")));
            BankTransaction row = saved.getFirst();
            assertThat(row.getSignedAmount().toPlainString()).isEqualTo("1234.5000");
            assertThat(row.getFingerprint())
                    .isEqualTo(TransactionNormalizer.fingerprint(
                            ACCOUNT, LocalDate.of(2026, 12, 2), new BigDecimal("1234.5000"), "DEPOSIT", null, null));
            assertThat(row.getDescription()).isEqualTo("Deposit");
            assertThat(row.getNormalizedDescription()).isEqualTo("DEPOSIT");
            assertThat(row.getFirstObservedAt()).isEqualTo(OBSERVED);
            assertThat(row.isArrivedAfterApproval()).isFalse();
        }
    }

    // ---- recompute (S5 calls it) ----------------------------------------------------------------

    @Nested
    class Recompute {

        @Test
        void itMovesTheBaselineBackWhenTheLatestAcknowledgedStatementIsGone() {
            existingProfile(LocalDate.of(2026, 12, 1));
            BankStatement earlier = new BankStatement();
            earlier.setStartDate(LocalDate.of(2026, 9, 1));
            when(statements.findFirstByGlAccountIdAndStatusAndGapAcknowledgementIsNotNullOrderByStartDateDesc(
                            ACCOUNT, BankStatementStatus.COMMITTED))
                    .thenReturn(Optional.of(earlier));

            assertThat(intake.recomputeBaseline(ACCOUNT, ACTOR, "superseded the December statement"))
                    .contains(LocalDate.of(2026, 9, 1));
            verify(audit)
                    .record(
                            BankRecAuditRecorder.BANK_ACCOUNT_PROFILE,
                            ACCOUNT,
                            BankRecAuditRecorder.BANK_ACCOUNT_BASELINE_SET,
                            ACTOR,
                            "superseded the December statement",
                            "2026-12-01",
                            "2026-09-01");
        }

        @Test
        void anUnchangedValueIsNeitherWrittenNorAudited() {
            existingProfile(LocalDate.of(2026, 9, 1));
            BankStatement same = new BankStatement();
            same.setStartDate(LocalDate.of(2026, 9, 1));
            when(statements.findFirstByGlAccountIdAndStatusAndGapAcknowledgementIsNotNullOrderByStartDateDesc(
                            ACCOUNT, BankStatementStatus.COMMITTED))
                    .thenReturn(Optional.of(same));

            assertThat(intake.recomputeBaseline(ACCOUNT, ACTOR, null)).contains(LocalDate.of(2026, 9, 1));
            verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
            verify(profiles, never()).save(any());
        }

        @Test
        void anAccountWithoutAProfileHasNoBaseline() {
            when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());
            assertThat(intake.recomputeBaseline(ACCOUNT, ACTOR, null)).isEmpty();
            verify(audit, never()).record(any(), any(), any(), any(), any(), isNull(), any());
        }
    }
}
