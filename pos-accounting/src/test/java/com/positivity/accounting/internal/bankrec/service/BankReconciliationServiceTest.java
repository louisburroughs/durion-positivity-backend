package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.ACCOUNT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.STATEMENT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.snapshot;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.statement;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.terms;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationImportRequest;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationApiStatus;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCreateRequest;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.exception.AccountNotReconcilableException;
import com.positivity.accounting.internal.exception.BankStatementParseException;
import com.positivity.accounting.internal.exception.ReconciliationAlreadyFinalizedException;
import com.positivity.accounting.internal.exception.ReconciliationNotBalancedException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link BankReconciliationServiceImpl} (Story F2 #965; S4 #2303): create from a COMMITTED statement with
 * its refusals and replay (§4.1, §6.3), the live header, finalize over the live difference, and the F2
 * CSV import that S3 retires.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankReconciliationServiceImpl — create, read, finalize (#2303)")
class BankReconciliationServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private BankReconciliationRepository reconciliationRepository;

    @Mock
    private BankStatementRepository statementRepository;

    @Mock
    private BankTransactionRepository transactionRepository;

    @Mock
    private BankReconciliationGlMatchRepository glMatchRepository;

    @Mock
    private BankReconciliationAdjustmentRepository adjustmentRepository;

    @Mock
    private GLAccountRepository glAccountRepository;

    @Mock
    private ReconciliationCalculator calculator;

    @Mock
    private BankRecAuditRecorder auditRecorder;

    @Mock
    private ReconciliationReviewService reviewService;

    private BankReconciliationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BankReconciliationServiceImpl(
                clock,
                reconciliationRepository,
                statementRepository,
                transactionRepository,
                glMatchRepository,
                adjustmentRepository,
                glAccountRepository,
                new BankCashAccounts(glAccountRepository, clock),
                calculator,
                new ReconciliationSupport(reconciliationRepository, calculator, clock),
                auditRecorder,
                usd(),
                reviewService);
        lenient().when(calculator.compute(any())).thenReturn(snapshot(terms("0", "0")));
    }

    private static GLAccount bankAccount() {
        GLAccount account = new GLAccount(ACCOUNT_ID);
        account.setAccountCode("1000");
        account.setAccountName("Cash");
        account.setReconcilable(true);
        account.setAccountSubtype(AccountSubtype.BANK_CASH);
        return account;
    }

    private static ReconciliationCreateRequest createRequest(UUID requestId) {
        return ReconciliationCreateRequest.builder()
                .glAccountId(ACCOUNT_ID)
                .requestId(requestId)
                .statementId(STATEMENT_ID)
                .build();
    }

    @Nested
    @DisplayName("create (§4.1)")
    class Create {

        private final UUID requestId = UUID.fromString("019a0000-0000-7000-8000-000000000001");

        @BeforeEach
        void account() {
            when(glAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(bankAccount()));
        }

        @Test
        @DisplayName("copies the statement window and balances, attributes the period, audits the create")
        void createsFromACommittedStatement() {
            BankStatement statement = statement(STATEMENT_ID, START, END, "Opened the account this month");
            when(reconciliationRepository.findByRequestId(requestId)).thenReturn(Optional.empty());
            when(statementRepository.findById(STATEMENT_ID)).thenReturn(Optional.of(statement));
            when(reconciliationRepository.findByStatementIdAndStatusIn(eq(STATEMENT_ID), anyCollection()))
                    .thenReturn(List.of());
            when(reconciliationRepository.saveAndFlush(any())).thenAnswer(inv -> {
                BankReconciliation saved = inv.getArgument(0);
                saved.setReconciliationId(RECON_ID);
                return saved;
            });

            BankReconciliationResponse response = service.create(createRequest(requestId));

            ArgumentCaptor<BankReconciliation> captor = ArgumentCaptor.forClass(BankReconciliation.class);
            verify(reconciliationRepository).saveAndFlush(captor.capture());
            BankReconciliation saved = captor.getValue();
            assertThat(saved.getStatementStartDate()).isEqualTo(START);
            assertThat(saved.getStatementEndDate()).isEqualTo(END);
            assertThat(saved.getStatementOpeningBalance()).isEqualByComparingTo("1000");
            assertThat(saved.getStatementClosingBalance()).isEqualByComparingTo("1250");
            assertThat(saved.getAccountingPeriodCode()).isEqualTo("2026-09");
            assertThat(saved.getRequestId()).isEqualTo(requestId);
            assertThat(saved.getStatus()).isEqualTo(ReconciliationStatus.IN_PROGRESS);
            assertThat(response.isReplayed()).isFalse();
            assertThat(response.getStatus()).isEqualTo(ReconciliationApiStatus.IN_PROGRESS);
            verify(auditRecorder)
                    .record(
                            eq(BankRecAuditRecorder.BANK_RECONCILIATION),
                            eq(RECON_ID),
                            eq(BankRecAuditRecorder.RECONCILIATION_CREATE),
                            any(),
                            isNull(),
                            isNull(),
                            any());
        }

        @Test
        @DisplayName("a statementless body answers BANK_ACCOUNT_FEED_NOT_LINKED in phase 1")
        void statementlessBodyRefused() {
            ReconciliationCreateRequest request = ReconciliationCreateRequest.builder()
                    .glAccountId(ACCOUNT_ID)
                    .requestId(requestId)
                    .windowStartDate(START)
                    .windowEndDate(END)
                    .closingBalance(BigDecimal.TEN)
                    .openingBalance(BigDecimal.ONE)
                    .build();

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.BANK_ACCOUNT_FEED_NOT_LINKED));
        }

        @Test
        @DisplayName("an IN_PROGRESS reconciliation of the statement answers RECONCILIATION_WINDOW_ALREADY_RECONCILED")
        void activeReconciliationRefuses() {
            BankReconciliation existing = reconciliation();
            when(reconciliationRepository.findByRequestId(requestId)).thenReturn(Optional.empty());
            when(statementRepository.findById(STATEMENT_ID))
                    .thenReturn(Optional.of(statement(STATEMENT_ID, START, END, null)));
            when(reconciliationRepository.findByStatementIdAndStatusIn(eq(STATEMENT_ID), anyCollection()))
                    .thenReturn(List.of(existing));

            assertThatThrownBy(() -> service.create(createRequest(requestId)))
                    .isInstanceOfSatisfying(BankRecException.class, e -> {
                        assertThat(e.code()).isEqualTo(BankRecErrorCode.RECONCILIATION_WINDOW_ALREADY_RECONCILED);
                        assertThat(e.fieldErrors()).containsEntry("reconciliationId", RECON_ID.toString());
                    });
            verify(reconciliationRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a FINALIZED reconciliation without a successor refuses; one with a successor does not")
        void finalizedWithoutSuccessorRefuses() {
            BankReconciliation finalized = reconciliation();
            finalized.setStatus(ReconciliationStatus.FINALIZED);
            when(reconciliationRepository.findByRequestId(requestId)).thenReturn(Optional.empty());
            when(statementRepository.findById(STATEMENT_ID))
                    .thenReturn(Optional.of(statement(STATEMENT_ID, START, END, null)));
            when(reconciliationRepository.findByStatementIdAndStatusIn(eq(STATEMENT_ID), anyCollection()))
                    .thenReturn(List.of(finalized));

            assertThatThrownBy(() -> service.create(createRequest(requestId)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code())
                                    .isEqualTo(BankRecErrorCode.RECONCILIATION_WINDOW_ALREADY_RECONCILED));

            finalized.setSupersededByReconciliationId(UUID.randomUUID());
            when(reconciliationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            assertThat(service.create(createRequest(requestId)).getStatus())
                    .isEqualTo(ReconciliationApiStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("a concurrent create losing the partial unique race answers the same 409")
        void concurrentCreateRefused() {
            when(reconciliationRepository.findByRequestId(requestId)).thenReturn(Optional.empty());
            when(statementRepository.findById(STATEMENT_ID))
                    .thenReturn(Optional.of(statement(STATEMENT_ID, START, END, null)));
            when(reconciliationRepository.findByStatementIdAndStatusIn(eq(STATEMENT_ID), anyCollection()))
                    .thenReturn(List.of());
            when(reconciliationRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("bank_reconciliation_active_statement_uk"));

            assertThatThrownBy(() -> service.create(createRequest(requestId)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code())
                                    .isEqualTo(BankRecErrorCode.RECONCILIATION_WINDOW_ALREADY_RECONCILED));
        }

        @Test
        @DisplayName("a statement of another account, or not COMMITTED, is not found")
        void statementNotFound() {
            BankStatement superseded = statement(STATEMENT_ID, START, END, null);
            superseded.setStatus(BankStatementStatus.SUPERSEDED);
            when(reconciliationRepository.findByRequestId(requestId)).thenReturn(Optional.empty());
            when(statementRepository.findById(STATEMENT_ID)).thenReturn(Optional.of(superseded));

            assertThatThrownBy(() -> service.create(createRequest(requestId)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.BANK_STATEMENT_NOT_FOUND));
        }

        @Test
        @DisplayName("the same requestId and payload replays; another payload is IDEMPOTENCY_CONFLICT")
        void replay() {
            BankReconciliation original = reconciliation();
            original.setRequestId(requestId);
            when(reconciliationRepository.findByRequestId(requestId)).thenReturn(Optional.of(original));

            BankReconciliationResponse replayed = service.create(createRequest(requestId));
            assertThat(replayed.isReplayed()).isTrue();
            assertThat(replayed.getReconciliationId()).isEqualTo(RECON_ID);

            ReconciliationCreateRequest other = createRequest(requestId);
            other.setStatementId(UUID.randomUUID());
            assertThatThrownBy(() -> service.create(other))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.IDEMPOTENCY_CONFLICT));
            verify(reconciliationRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("read and finalize")
    class ReadAndFinalize {

        @Test
        @DisplayName("get serves the live terms without storing them")
        void getIsLive() {
            BankReconciliation recon = reconciliation();
            when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
            when(calculator.compute(recon)).thenReturn(snapshot(terms("12.34", "-45.67")));

            BankReconciliationResponse response = service.get(RECON_ID);

            assertThat(response.getDifference()).isEqualByComparingTo("12.34");
            assertThat(response.getOpeningDifference()).isEqualByComparingTo("-45.67");
            verify(reconciliationRepository, never()).save(any());
        }

        @Test
        @DisplayName("get of an unknown id is RECONCILIATION_NOT_FOUND")
        void getNotFound() {
            when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.get(RECON_ID)).isInstanceOf(ReconciliationNotFoundException.class);
        }

        @Test
        @DisplayName("finalize passes at a live difference of exactly one minor unit")
        void finalizeWithinTolerance() {
            BankReconciliation recon = reconciliation();
            when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
            when(calculator.compute(recon)).thenReturn(snapshot(terms("0.01", null)));

            BankReconciliationResponse response = service.finalizeReconciliation(RECON_ID);

            assertThat(response.getStatus()).isEqualTo(ReconciliationApiStatus.FINALIZED);
            assertThat(recon.getFinalizedAt()).isEqualTo(Instant.now(clock));
            assertThat(recon.getDifference()).isEqualByComparingTo("0.01");
        }

        @Test
        @DisplayName("finalize refuses a live difference beyond tolerance with the difference as a field")
        void finalizeRefusesUnbalanced() {
            BankReconciliation recon = reconciliation();
            when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));
            when(calculator.compute(recon)).thenReturn(snapshot(terms("0.02", null)));

            assertThatThrownBy(() -> service.finalizeReconciliation(RECON_ID))
                    .isInstanceOfSatisfying(
                            ReconciliationNotBalancedException.class,
                            e -> assertThat(e.getDifference()).isEqualByComparingTo("0.02"));
            assertThat(recon.getStatus()).isEqualTo(ReconciliationStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("finalizing twice is refused, not repeated")
        void finalizeTwiceRefused() {
            BankReconciliation recon = reconciliation();
            recon.setStatus(ReconciliationStatus.FINALIZED);
            when(reconciliationRepository.findById(RECON_ID)).thenReturn(Optional.of(recon));

            assertThatThrownBy(() -> service.finalizeReconciliation(RECON_ID))
                    .isInstanceOf(ReconciliationAlreadyFinalizedException.class);
        }
    }

    @Nested
    @DisplayName("F2 CSV import (retired by S3)")
    class Import {

        @BeforeEach
        void stubs() {
            lenient().when(glAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(bankAccount()));
            lenient().when(statementRepository.save(any(BankStatement.class))).thenAnswer(inv -> {
                BankStatement statement = inv.getArgument(0);
                statement.setStatementId(STATEMENT_ID);
                return statement;
            });
            lenient()
                    .when(reconciliationRepository.save(any(BankReconciliation.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("parses the CSV into one COMMITTED statement and UNMATCHED rows, E1 by construction")
        void importParsesCsv() {
            BankReconciliationResponse response = service.importStatement(
                    request("date,description,amount,reference\n2026-06-15,ACH DEPOSIT,1500.00,REF-1\n"
                            + "2026-06-20,SERVICE FEE,(12.50),REF-2"));

            ArgumentCaptor<BankStatement> statementCaptor = ArgumentCaptor.forClass(BankStatement.class);
            verify(statementRepository).save(statementCaptor.capture());
            BankStatement statement = statementCaptor.getValue();
            assertThat(statement.getSourceKind()).isEqualTo(SourceKind.FILE_IMPORT);
            assertThat(statement.getActivityTotal()).isEqualByComparingTo("1487.50");
            assertThat(statement.getOpeningBalance()).isEqualByComparingTo("512.50");
            List<BankTransaction> lines = savedTransactions();
            assertThat(lines)
                    .extracting(
                            BankTransaction::getSourceRowNumber,
                            BankTransaction::getSignedAmount,
                            BankTransaction::getStatus)
                    .containsExactly(
                            tuple(1, new BigDecimal("1500.00"), BankTransactionStatus.UNMATCHED),
                            tuple(2, new BigDecimal("-12.50"), BankTransactionStatus.UNMATCHED));
            assertThat(lines).allSatisfy(l -> assertThat(l.getSettlementState()).isEqualTo(SettlementState.POSTED));
            assertThat(response.getStatementId()).isEqualTo(STATEMENT_ID);
            assertThat(response.getAccountingPeriodCode()).isEqualTo("2026-06");
            assertThat(response.getStatementOpeningBalance()).isEqualByComparingTo("512.50");
        }

        @Test
        @DisplayName("unquotes an embedded comma, a doubled quote and a quoted last field")
        void importParsesQuotedFields() {
            service.importStatement(request(
                    "2026-06-15,\"ACME, INC\",100.00,\"REF-1\"\n" + "2026-06-16,\"Joe\"\"s Diner\",50.00,REF-2"));
            List<BankTransaction> lines = savedTransactions();
            assertThat(lines.get(0).getDescription()).isEqualTo("ACME, INC");
            assertThat(lines.get(0).getReference()).isEqualTo("REF-1");
            assertThat(lines.get(1).getDescription()).isEqualTo("Joe\"s Diner");
        }

        @Test
        @DisplayName("refuses a malformed amount and a header-less first row with a bad date")
        void importRefusesMalformedRows() {
            assertThatThrownBy(() -> service.importStatement(request("2026-06-15,ACH DEPOSIT,NOT_A_NUMBER,REF-1")))
                    .isInstanceOf(BankStatementParseException.class);
            assertThatThrownBy(() -> service.importStatement(request("2026-13-45,BAD DATE,100.00,REF-1")))
                    .isInstanceOf(BankStatementParseException.class);
        }

        @Test
        @DisplayName("refuses a non-reconcilable account")
        void importRefusesNonReconcilable() {
            GLAccount revenue = new GLAccount(ACCOUNT_ID);
            revenue.setAccountCode("4000");
            revenue.setReconcilable(false);
            when(glAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(revenue));

            assertThatThrownBy(() -> service.importStatement(request("2026-06-15,ACH DEPOSIT,1500.00,REF-1")))
                    .isInstanceOf(AccountNotReconcilableException.class);
            verify(reconciliationRepository, never()).save(any());
        }

        private BankReconciliationImportRequest request(String csv) {
            return BankReconciliationImportRequest.builder()
                    .glAccountId(ACCOUNT_ID)
                    .periodStartDate(LocalDate.of(2026, 6, 1))
                    .periodEndDate(LocalDate.of(2026, 6, 30))
                    .statementDate(LocalDate.of(2026, 6, 30))
                    .statementEndingBalance(new BigDecimal("2000.0000"))
                    .currency("USD")
                    .csv(csv)
                    .build();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<BankTransaction> savedTransactions() {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(captor.capture());
        return captor.getValue();
    }
}
