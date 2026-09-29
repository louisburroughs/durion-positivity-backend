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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationApiStatus;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCreateRequest;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
    private BankReconciliationMatchRepository matchRepository;

    @Mock
    private BankReconciliationOutstandingItemRepository itemRepository;

    @Mock
    private AccountingAuditLogRepository auditLogRepository;

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
                reconciliationRepository,
                statementRepository,
                matchRepository,
                itemRepository,
                auditLogRepository,
                new BankCashAccounts(glAccountRepository, clock),
                calculator,
                new ReconciliationSupport(reconciliationRepository, calculator, clock),
                auditRecorder,
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
    }
}
