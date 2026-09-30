package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.ACCOUNT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.STATEMENT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.amount;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.statement;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.BankStatementCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.BankStatementListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankStatementResponse;
import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.BankTransactionIntake;
import com.positivity.accounting.internal.bankrec.intake.IntakeContext;
import com.positivity.accounting.internal.bankrec.intake.IntakeResult;
import com.positivity.accounting.internal.bankrec.intake.ReconciliationStarter;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.repository.StatementCounts;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.BankTransactionObserved;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.Change;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.SettlementState;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link BankStatementServiceImpl} (SPEC §4.3 item 3, §6.1, §6.3; story S2, #2301): the manual statement's shape
 * validation, its replay and reuse rules, the batch and context it hands the intake port, the optional
 * reconciliation start and the {@code BANK_STATEMENT_CREATE} audit row; and the statement reads.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankStatementServiceImpl — manual statement entry and statement reads (#2301)")
class BankStatementServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("0190a000-0000-7000-8000-000000000002");
    private static final String ACK = "Account opened at the new bank on 2026-09-01";
    private static final BankCashAccount CASH = new BankCashAccount(ACCOUNT_ID, "1000", "Cash");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private BankTransactionIntake intake;

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
    private ReconciliationStarter reconciliationStarter;

    private BankStatementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BankStatementServiceImpl(
                intake,
                bankCashAccounts,
                usd(),
                statements,
                transactions,
                profiles,
                reconciliations,
                audit,
                clock,
                reconciliationStarter);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private static BankStatementCreateRequest.Header header() {
        return BankStatementCreateRequest.Header.builder()
                .statementRef("2026-09")
                .startDate(START)
                .endDate(END)
                .openingBalance(amount("1000.00"))
                .closingBalance(amount("1250.00"))
                .build();
    }

    private static BankStatementCreateRequest.Transaction row(String signedAmount) {
        return BankStatementCreateRequest.Transaction.builder()
                .date(START.plusDays(1))
                .signedAmount(amount(signedAmount))
                .description("ACH DEPOSIT ACME")
                .reference("DEP-1")
                .checkNumber("1042")
                .build();
    }

    /** A valid request: one signed row of 250.00 in USD. */
    private static BankStatementCreateRequest request() {
        return BankStatementCreateRequest.builder()
                .glAccountId(ACCOUNT_ID)
                .requestId(REQUEST_ID)
                .statement(header())
                .transactions(new ArrayList<>(List.of(row("250.00"))))
                .currency("USD")
                .build();
    }

    private static IntakeResult committed(int count, int modified) {
        return new IntakeResult(STATEMENT_ID, List.of(), count, 0, modified, START, true);
    }

    private static BankReconciliation reconciliationWith(ReconciliationStatus status) {
        BankReconciliation recon = reconciliation();
        recon.setStatus(status);
        return recon;
    }

    private static Map<String, String> fieldErrorsOf(Runnable call) {
        BankRecException refused = catchBankRec(call);
        assertThat(refused.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR);
        return refused.fieldErrors();
    }

    private static BankRecException catchBankRec(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(BankRecException.class);
        return (BankRecException) thrown;
    }

    private Map<String, String> validationErrors(BankStatementCreateRequest request) {
        Map<String, String> errors = fieldErrorsOf(() -> service.createManualStatement(request));
        verifyNoInteractions(intake, statements, audit);
        return errors;
    }

    /** The committed statement, its display values, counts and reconciliations as the repositories answer them. */
    private BankStatement stubCommitted(String gapAck) {
        BankStatement stored = statement(STATEMENT_ID, START, END, gapAck);
        lenient().when(statements.findById(STATEMENT_ID)).thenReturn(Optional.of(stored));
        lenient().when(bankCashAccounts.displayValues(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, CASH));
        lenient()
                .when(transactions.countByStatementIdIn(
                        List.of(STATEMENT_ID), BankTransactionStatus.POSSIBLE_DUPLICATE))
                .thenReturn(List.of(new StatementCounts(STATEMENT_ID, 3L, 1L)));
        lenient()
                .when(reconciliations.findByStatementIdOrderByStatementStartDateAsc(STATEMENT_ID))
                .thenReturn(List.of(reconciliationWith(ReconciliationStatus.FINALIZED)));
        return stored;
    }

    private static void authenticateAs(String username) {
        TestingAuthenticationToken token = new TestingAuthenticationToken("principal", null);
        token.setAuthenticated(true);
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // ---- createManualStatement ---------------------------------------------------------------

    @Nested
    @DisplayName("createManualStatement — commit")
    class Commit {

        @Test
        @DisplayName("hands the intake a MANUAL_ENTRY batch and a context carrying the request id and hash")
        void batchAndContext() {
            stubCommitted(ACK);
            BankStatementCreateRequest request = request();
            request.setGapAcknowledgement(ACK);
            when(intake.accept(any(), any())).thenReturn(committed(1, 0));

            service.createManualStatement(request);

            ArgumentCaptor<BankTransactionsObservedV1> batch =
                    ArgumentCaptor.forClass(BankTransactionsObservedV1.class);
            ArgumentCaptor<IntakeContext> ctx = ArgumentCaptor.forClass(IntakeContext.class);
            verify(intake).accept(batch.capture(), ctx.capture());

            BankTransactionsObservedV1 sent = batch.getValue();
            assertThat(sent.sourceKind()).isEqualTo(BankTransactionsObservedV1.SourceKind.MANUAL_ENTRY);
            assertThat(sent.currency()).isEqualTo("USD");
            assertThat(sent.observedAt()).isEqualTo(NOW);
            assertThat(sent.connectorCode()).isNull();
            assertThat(sent.statement().statementRef()).isEqualTo("2026-09");
            assertThat(sent.statement().startDate()).isEqualTo(START);
            assertThat(sent.statement().endDate()).isEqualTo(END);
            assertThat(sent.statement().openingBalance()).isEqualByComparingTo("1000.00");
            assertThat(sent.statement().closingBalance()).isEqualByComparingTo("1250.00");
            BankTransactionObserved only = sent.transactions().getFirst();
            assertThat(only.sourceRowNumber()).isEqualTo(1);
            assertThat(only.change()).isEqualTo(Change.ADDED);
            assertThat(only.settlementState()).isEqualTo(SettlementState.POSTED);
            assertThat(only.transactionDate()).isEqualTo(START.plusDays(1));
            assertThat(only.signedAmount()).isEqualByComparingTo("250.00");
            assertThat(only.description()).isEqualTo("ACH DEPOSIT ACME");
            assertThat(only.reference()).isEqualTo("DEP-1");
            assertThat(only.checkNumber()).isEqualTo("1042");

            IntakeContext context = ctx.getValue();
            assertThat(context.glAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(context.actor()).isEqualTo("SYSTEM");
            assertThat(context.gapAcknowledgement()).isEqualTo(ACK);
            assertThat(context.sourceRef()).isNull();
            assertThat(context.requestId()).isEqualTo(REQUEST_ID);
            assertThat(context.requestHash()).isEqualTo(BankStatementServiceImpl.hash(request));
            assertThat(context.defaultColumnMapping()).isNull();
            assertThat(context.confirmedDistinctRows()).isEmpty();
            assertThat(context.supersedesStatementId()).isNull();
            assertThat(context.supersessionJustification()).isNull();
        }

        @Test
        @DisplayName("credit is cash in, debit is cash out; rows are numbered in order and blank text becomes null")
        void debitCreditAndBlanks() {
            stubCommitted(null);
            BankStatementCreateRequest request = request();
            request.getStatement().setStatementRef("  ");
            request.setTransactions(List.of(
                    BankStatementCreateRequest.Transaction.builder()
                            .date(START)
                            .credit(amount("300.00"))
                            .description("Wire in")
                            .reference(" ")
                            .checkNumber("")
                            .build(),
                    BankStatementCreateRequest.Transaction.builder()
                            .date(END)
                            .debit(amount("50.00"))
                            .description("Check out")
                            .build()));
            when(intake.accept(any(), any())).thenReturn(committed(2, 0));

            service.createManualStatement(request);

            ArgumentCaptor<BankTransactionsObservedV1> batch =
                    ArgumentCaptor.forClass(BankTransactionsObservedV1.class);
            verify(intake).accept(batch.capture(), any());
            assertThat(batch.getValue().statement().statementRef()).isNull();
            List<BankTransactionObserved> rows = batch.getValue().transactions();
            assertThat(rows)
                    .extracting(BankTransactionObserved::sourceRowNumber)
                    .containsExactly(1, 2);
            assertThat(rows.get(0).signedAmount()).isEqualByComparingTo("300.00");
            assertThat(rows.get(0).reference()).isNull();
            assertThat(rows.get(0).checkNumber()).isNull();
            assertThat(rows.get(1).signedAmount()).isEqualByComparingTo("-50.00");
            assertThat(rows.get(1).reference()).isNull();
            assertThat(rows.get(1).checkNumber()).isNull();
        }

        @Test
        @DisplayName("the given currency is trimmed and upper-cased; no profile or ledger lookup is made")
        void currencyGiven() {
            stubCommitted(null);
            BankStatementCreateRequest request = request();
            request.setCurrency(" usd ");
            when(intake.accept(any(), any())).thenReturn(committed(1, 0));

            service.createManualStatement(request);

            ArgumentCaptor<BankTransactionsObservedV1> batch =
                    ArgumentCaptor.forClass(BankTransactionsObservedV1.class);
            verify(intake).accept(batch.capture(), any());
            assertThat(batch.getValue().currency()).isEqualTo("USD");
            verifyNoInteractions(profiles);
        }

        @Test
        @DisplayName("without a currency the account profile's currency is used")
        void currencyFromProfile() {
            stubCommitted(null);
            BankStatementCreateRequest request = request();
            request.setCurrency(null);
            BankAccountProfile profile = new BankAccountProfile(ACCOUNT_ID);
            profile.setCurrency("CAD");
            when(profiles.findById(ACCOUNT_ID)).thenReturn(Optional.of(profile));
            when(intake.accept(any(), any())).thenReturn(committed(1, 0));

            service.createManualStatement(request);

            ArgumentCaptor<BankTransactionsObservedV1> batch =
                    ArgumentCaptor.forClass(BankTransactionsObservedV1.class);
            verify(intake).accept(batch.capture(), any());
            assertThat(batch.getValue().currency()).isEqualTo("CAD");
        }

        @Test
        @DisplayName("without a currency or a profile the ledger's functional currency is used")
        void currencyFromLedger() {
            stubCommitted(null);
            BankStatementCreateRequest request = request();
            request.setCurrency(null);
            when(profiles.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
            when(intake.accept(any(), any())).thenReturn(committed(1, 0));
            // A non-USD ledger, so a hardcoded "USD" fallback cannot pass for the functional currency.
            BankStatementServiceImpl eurLedger = new BankStatementServiceImpl(
                    intake,
                    bankCashAccounts,
                    new FunctionalCurrency(new LedgerCurrency("EUR")),
                    statements,
                    transactions,
                    profiles,
                    reconciliations,
                    audit,
                    clock,
                    reconciliationStarter);

            eurLedger.createManualStatement(request);

            ArgumentCaptor<BankTransactionsObservedV1> batch =
                    ArgumentCaptor.forClass(BankTransactionsObservedV1.class);
            verify(intake).accept(batch.capture(), any());
            assertThat(batch.getValue().currency()).isEqualTo("EUR");
        }

        @Test
        @DisplayName("answers the committed statement with display values, counts, modifiedCount and links")
        void response() {
            stubCommitted(ACK);
            when(intake.accept(any(), any())).thenReturn(committed(3, 2));

            BankStatementResponse response = service.createManualStatement(request());

            assertThat(response.getStatementId()).isEqualTo(STATEMENT_ID);
            assertThat(response.getGlAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.getAccountCode()).isEqualTo("1000");
            assertThat(response.getAccountName()).isEqualTo("Cash");
            assertThat(response.getStatus()).isEqualTo(BankStatementStatus.COMMITTED);
            assertThat(response.getGapAcknowledgement()).isEqualTo(ACK);
            assertThat(response.getBankTransactionCount()).isEqualTo(3L);
            assertThat(response.getPossibleDuplicateCount()).isEqualTo(1L);
            assertThat(response.getModifiedCount()).isEqualTo(2L);
            assertThat(response.isReplayed()).isFalse();
            assertThat(response.getReconciliations()).singleElement().satisfies(link -> {
                assertThat(link.getReconciliationId()).isEqualTo(BankRecFixtures.RECON_ID);
                assertThat(link.getStatus()).isEqualTo("FINALIZED");
            });
        }

        @Test
        @DisplayName("writes one BANK_STATEMENT_CREATE audit row with the window, balances and row count")
        void auditRow() {
            stubCommitted(ACK);
            when(intake.accept(any(), any())).thenReturn(committed(7, 0));

            service.createManualStatement(request());

            verify(audit)
                    .record(
                            BankRecAuditRecorder.BANK_STATEMENT,
                            STATEMENT_ID,
                            BankRecAuditRecorder.BANK_STATEMENT_CREATE,
                            "SYSTEM",
                            ACK,
                            null,
                            "glAccountId=" + ACCOUNT_ID + ", window=2026-09-01..2026-09-30, opening=1000.0000,"
                                    + " closing=1250.0000, transactions=7");
        }

        @Test
        @DisplayName("an authenticated caller is the actor on the intake context and the audit row (ADR-0018)")
        void authenticatedActor() {
            authenticateAs("preparer");
            stubCommitted(null);
            when(intake.accept(any(), any())).thenReturn(committed(1, 0));

            service.createManualStatement(request());

            ArgumentCaptor<IntakeContext> ctx = ArgumentCaptor.forClass(IntakeContext.class);
            verify(intake).accept(any(), ctx.capture());
            assertThat(ctx.getValue().actor()).isEqualTo("preparer");
            verify(audit)
                    .record(
                            eq(BankRecAuditRecorder.BANK_STATEMENT),
                            eq(STATEMENT_ID),
                            eq(BankRecAuditRecorder.BANK_STATEMENT_CREATE),
                            eq("preparer"),
                            any(),
                            any(),
                            anyString());
        }

        @Test
        @DisplayName("an authenticated context without a username falls back to SYSTEM")
        void authenticatedWithoutUsername() {
            TestingAuthenticationToken token = new TestingAuthenticationToken(" ", null);
            token.setAuthenticated(true);
            token.setDetails(Map.of());
            SecurityContextHolder.getContext().setAuthentication(token);
            stubCommitted(null);
            when(intake.accept(any(), any())).thenReturn(committed(1, 0));

            service.createManualStatement(request());

            ArgumentCaptor<IntakeContext> ctx = ArgumentCaptor.forClass(IntakeContext.class);
            verify(intake).accept(any(), ctx.capture());
            assertThat(ctx.getValue().actor()).isEqualTo("SYSTEM");
        }

        @Test
        @DisplayName("a supersession travels to the intake on the context")
        void supersession() {
            stubCommitted(null);
            UUID old = UUIDv7Generator.generate();
            BankStatementCreateRequest request = request();
            request.setSupersedesStatementId(old);
            request.setSupersessionJustification("The bank reissued September");
            when(intake.accept(any(), any())).thenReturn(committed(1, 0));

            service.createManualStatement(request);

            ArgumentCaptor<IntakeContext> ctx = ArgumentCaptor.forClass(IntakeContext.class);
            verify(intake).accept(any(), ctx.capture());
            assertThat(ctx.getValue().supersedesStatementId()).isEqualTo(old);
            assertThat(ctx.getValue().supersessionJustification()).isEqualTo("The bank reissued September");
        }

        @Test
        @DisplayName("startReconciliation = true starts one with a request id derived from the command's")
        void startsReconciliation() {
            stubCommitted(null);
            BankStatementCreateRequest request = request();
            request.setStartReconciliation(true);
            when(intake.accept(any(), any())).thenReturn(committed(1, 0));

            service.createManualStatement(request);

            UUID derived = UUID.nameUUIDFromBytes(
                    ("BANK_STATEMENT_RECONCILIATION:" + REQUEST_ID).getBytes(StandardCharsets.UTF_8));
            verify(reconciliationStarter).start(ACCOUNT_ID, STATEMENT_ID, derived);
        }

        @Test
        @DisplayName("startReconciliation false or absent starts none")
        void startsNoReconciliation() {
            stubCommitted(null);
            when(intake.accept(any(), any())).thenReturn(committed(1, 0));
            BankStatementCreateRequest off = request();
            off.setStartReconciliation(false);

            service.createManualStatement(off);
            service.createManualStatement(request());

            verifyNoInteractions(reconciliationStarter);
        }

        @Test
        @DisplayName("a committed statement the repository cannot find is an IllegalStateException, not a 404")
        void committedStatementMissing() {
            when(statements.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());
            when(intake.accept(any(), any())).thenReturn(committed(1, 0));
            when(statements.findById(STATEMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createManualStatement(request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(STATEMENT_ID.toString());
            verifyNoInteractions(audit);
        }
    }

    @Nested
    @DisplayName("createManualStatement — replay and reuse of the requestId (§6.3)")
    class Idempotency {

        @Test
        @DisplayName("the same payload replays the original statement without a second commit or audit row")
        void replay() {
            BankStatementCreateRequest request = request();
            BankStatement earlier = stubCommitted(null);
            earlier.setRequestId(REQUEST_ID);
            earlier.setRequestHash(BankStatementServiceImpl.hash(request));
            when(statements.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(earlier));

            BankStatementResponse response = service.createManualStatement(request);

            assertThat(response.isReplayed()).isTrue();
            assertThat(response.getStatementId()).isEqualTo(STATEMENT_ID);
            assertThat(response.getAccountCode()).isEqualTo("1000");
            assertThat(response.getBankTransactionCount()).isEqualTo(3L);
            assertThat(response.getModifiedCount()).isNull();
            assertThat(response.getReconciliations()).hasSize(1);
            verifyNoInteractions(intake, audit, reconciliationStarter, profiles);
        }

        @Test
        @DisplayName("a different payload under a used requestId is 409 IDEMPOTENCY_CONFLICT")
        void reuse() {
            BankStatement earlier = statement(STATEMENT_ID, START, END, null);
            earlier.setRequestId(REQUEST_ID);
            earlier.setRequestHash(BankStatementServiceImpl.hash(request()));
            when(statements.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(earlier));
            BankStatementCreateRequest changed = request();
            changed.getTransactions().getFirst().setSignedAmount(amount("251.00"));

            BankRecException refused = catchBankRec(() -> service.createManualStatement(changed));

            assertThat(refused.code()).isEqualTo(BankRecErrorCode.IDEMPOTENCY_CONFLICT);
            assertThat(refused).hasMessageContaining(REQUEST_ID.toString());
            verifyNoInteractions(intake, audit, reconciliationStarter);
        }
    }

    @Nested
    @DisplayName("createManualStatement — request shape (400 VALIDATION_ERROR naming every field)")
    class Shape {

        @Test
        @DisplayName("an empty request names the account, the request id, the header and the transactions")
        void empty() {
            assertThat(validationErrors(new BankStatementCreateRequest()))
                    .containsExactly(
                            entry("glAccountId", "is required"),
                            entry("requestId", "is required"),
                            entry("statement", "is required"),
                            entry("transactions", "at least one transaction is required"));
        }

        @Test
        @DisplayName("a requestId that is not a UUIDv7 is refused")
        void requestIdVersion() {
            BankStatementCreateRequest request = request();
            request.setRequestId(UUID.fromString("0190a000-0000-4000-8000-000000000002"));

            assertThat(validationErrors(request)).containsExactly(entry("requestId", "must be a UUIDv7"));
        }

        @Test
        @DisplayName("a currency that is not three letters or not ISO 4217 is refused")
        void currency() {
            BankStatementCreateRequest shortCode = request();
            shortCode.setCurrency("US");
            BankStatementCreateRequest unknown = request();
            unknown.setCurrency("ZZZ");

            assertThat(validationErrors(shortCode)).containsExactly(entry("currency", "must be an ISO 4217 code"));
            assertThat(validationErrors(unknown)).containsExactly(entry("currency", "must be an ISO 4217 code"));
        }

        @Test
        @DisplayName("an empty header names each required field")
        void headerFields() {
            BankStatementCreateRequest request = request();
            request.setStatement(new BankStatementCreateRequest.Header());

            assertThat(validationErrors(request))
                    .containsExactly(
                            entry("statement.startDate", "is required"),
                            entry("statement.endDate", "is required"),
                            entry("statement.openingBalance", "is required"),
                            entry("statement.closingBalance", "is required"));
        }

        @Test
        @DisplayName("a start date without an end date names only the end date")
        void startWithoutEnd() {
            BankStatementCreateRequest request = request();
            request.getStatement().setEndDate(null);

            assertThat(validationErrors(request)).containsExactly(entry("statement.endDate", "is required"));
        }

        @Test
        @DisplayName("a window that ends before it starts is refused on startDate")
        void invertedWindow() {
            BankStatementCreateRequest request = request();
            request.getStatement().setStartDate(END);
            request.getStatement().setEndDate(START);

            assertThat(validationErrors(request))
                    .containsExactly(entry("statement.startDate", "must not be after statement.endDate"));
        }

        @Test
        @DisplayName("a statementRef longer than 64 characters is refused")
        void statementRefLength() {
            BankStatementCreateRequest request = request();
            request.getStatement().setStatementRef("R".repeat(65));

            assertThat(validationErrors(request))
                    .containsExactly(entry("statement.statementRef", "at most 64 characters"));
        }

        @Test
        @DisplayName("an empty transaction list is refused")
        void noTransactions() {
            BankStatementCreateRequest request = request();
            request.setTransactions(List.of());

            assertThat(validationErrors(request))
                    .containsExactly(entry("transactions", "at least one transaction is required"));
        }

        @Test
        @DisplayName("a null row, a missing date and a missing, blank or long description are named per row")
        void rowText() {
            BankStatementCreateRequest.Transaction noDate = row("1.00");
            noDate.setDate(null);
            BankStatementCreateRequest.Transaction noDescription = row("1.00");
            noDescription.setDescription(null);
            BankStatementCreateRequest.Transaction blank = row("1.00");
            blank.setDescription("   ");
            BankStatementCreateRequest.Transaction longDescription = row("1.00");
            longDescription.setDescription("D".repeat(501));
            BankStatementCreateRequest request = request();
            request.setTransactions(Arrays.asList(null, noDate, noDescription, blank, longDescription));

            assertThat(validationErrors(request))
                    .containsExactly(
                            entry("transactions[0]", "is required"),
                            entry("transactions[1].date", "is required"),
                            entry("transactions[2].description", "is required"),
                            entry("transactions[3].description", "is required"),
                            entry("transactions[4].description", "at most 500 characters"));
        }

        @Test
        @DisplayName("a reference over 255 or a check number over 32 characters is refused")
        void referenceAndCheckNumberLength() {
            BankStatementCreateRequest.Transaction row = row("1.00");
            row.setReference("R".repeat(256));
            row.setCheckNumber("9".repeat(33));
            BankStatementCreateRequest request = request();
            request.setTransactions(List.of(row));

            assertThat(validationErrors(request))
                    .containsExactly(
                            entry("transactions[0].reference", "at most 255 characters"),
                            entry("transactions[0].checkNumber", "at most 32 characters"));
        }

        @Test
        @DisplayName("a row needs exactly one of signedAmount, debit or credit")
        void exactlyOneAmount() {
            BankStatementCreateRequest.Transaction none = row("1.00");
            none.setSignedAmount(null);
            BankStatementCreateRequest.Transaction signedAndDebit = row("1.00");
            signedAndDebit.setDebit(amount("1.00"));
            BankStatementCreateRequest.Transaction debitAndCredit = row("1.00");
            debitAndCredit.setSignedAmount(null);
            debitAndCredit.setDebit(amount("1.00"));
            debitAndCredit.setCredit(amount("1.00"));
            BankStatementCreateRequest request = request();
            request.setTransactions(List.of(none, signedAndDebit, debitAndCredit));

            String detail = "exactly one of signedAmount, debit or credit is required";
            assertThat(validationErrors(request))
                    .containsExactly(
                            entry("transactions[0]", detail),
                            entry("transactions[1]", detail),
                            entry("transactions[2]", detail));
        }

        @Test
        @DisplayName("a zero amount, a negative debit and a negative credit are refused")
        void amountSign() {
            BankStatementCreateRequest.Transaction zero = row("0.00");
            BankStatementCreateRequest.Transaction negativeDebit = row("1.00");
            negativeDebit.setSignedAmount(null);
            negativeDebit.setDebit(amount("-5.00"));
            BankStatementCreateRequest.Transaction negativeCredit = row("1.00");
            negativeCredit.setSignedAmount(null);
            negativeCredit.setCredit(amount("-5.00"));
            BankStatementCreateRequest.Transaction zeroDebit = row("1.00");
            zeroDebit.setSignedAmount(null);
            zeroDebit.setDebit(BigDecimal.ZERO);
            BankStatementCreateRequest request = request();
            request.setTransactions(List.of(zero, negativeDebit, negativeCredit, zeroDebit));

            assertThat(validationErrors(request))
                    .containsExactly(
                            entry("transactions[0]", "the amount must not be zero"),
                            entry("transactions[1]", "debit and credit are positive numbers"),
                            entry("transactions[2]", "debit and credit are positive numbers"),
                            entry("transactions[3]", "the amount must not be zero"));
        }

        @Test
        @DisplayName("a negative signed amount is accepted: only debit and credit must be positive")
        void negativeSignedAmountIsValid() {
            stubCommitted(null);
            BankStatementCreateRequest request = request();
            request.setTransactions(List.of(row("-40.00")));
            when(intake.accept(any(), any())).thenReturn(committed(1, 0));

            service.createManualStatement(request);

            ArgumentCaptor<BankTransactionsObservedV1> batch =
                    ArgumentCaptor.forClass(BankTransactionsObservedV1.class);
            verify(intake).accept(batch.capture(), any());
            assertThat(batch.getValue().transactions().getFirst().signedAmount())
                    .isEqualByComparingTo("-40.00");
        }

        @Test
        @DisplayName("a gap acknowledgement or supersession justification over 1000 characters is refused")
        void longJustifications() {
            BankStatementCreateRequest request = request();
            request.setGapAcknowledgement("G".repeat(1001));
            request.setSupersessionJustification("S".repeat(1001));

            assertThat(validationErrors(request))
                    .containsExactly(
                            entry("gapAcknowledgement", "at most 1000 characters"),
                            entry("supersessionJustification", "at most 1000 characters"));
        }

        @Test
        @DisplayName("text at its limit passes the shape check")
        void atTheLimits() {
            stubCommitted(null);
            BankStatementCreateRequest.Transaction row = row("1.00");
            row.setDescription("D".repeat(500));
            row.setReference("R".repeat(255));
            row.setCheckNumber("9".repeat(32));
            BankStatementCreateRequest request = request();
            request.getStatement().setStatementRef("S".repeat(64));
            request.setTransactions(List.of(row));
            request.setGapAcknowledgement("G".repeat(1000));
            request.setSupersessionJustification("J".repeat(1000));
            request.setSupersedesStatementId(UUIDv7Generator.generate());
            when(intake.accept(any(), any())).thenReturn(committed(1, 0));

            assertThat(service.createManualStatement(request).getStatementId()).isEqualTo(STATEMENT_ID);
        }
    }

    @Nested
    @DisplayName("hash — the replay fingerprint")
    class Hash {

        @Test
        @DisplayName("currency, gap acknowledgement and justification compare trimmed and case-folded")
        void normalised() {
            BankStatementCreateRequest a = request();
            a.setCurrency("usd ");
            a.setGapAcknowledgement("  " + ACK + " ");
            a.setSupersedesStatementId(STATEMENT_ID);
            a.setSupersessionJustification(" The bank reissued September ");
            BankStatementCreateRequest b = request();
            b.setCurrency("USD");
            b.setGapAcknowledgement(ACK);
            b.setSupersedesStatementId(STATEMENT_ID);
            b.setSupersessionJustification("The bank reissued September");

            assertThat(BankStatementServiceImpl.hash(a)).isEqualTo(BankStatementServiceImpl.hash(b));
        }

        @Test
        @DisplayName("an absent currency differs from an explicit one")
        void absentCurrency() {
            BankStatementCreateRequest absent = request();
            absent.setCurrency(null);

            assertThat(BankStatementServiceImpl.hash(absent)).isNotEqualTo(BankStatementServiceImpl.hash(request()));
        }

        @Test
        @DisplayName("either supersession field alone changes the hash; neither keeps the earlier hash")
        void supersessionFields() {
            String plain = BankStatementServiceImpl.hash(request());
            BankStatementCreateRequest idOnly = request();
            idOnly.setSupersedesStatementId(STATEMENT_ID);
            BankStatementCreateRequest justificationOnly = request();
            justificationOnly.setSupersessionJustification("The bank reissued September");
            BankStatementCreateRequest both = request();
            both.setSupersedesStatementId(STATEMENT_ID);
            both.setSupersessionJustification("The bank reissued September");

            assertThat(List.of(
                            plain,
                            BankStatementServiceImpl.hash(idOnly),
                            BankStatementServiceImpl.hash(justificationOnly),
                            BankStatementServiceImpl.hash(both)))
                    .doesNotHaveDuplicates()
                    .allSatisfy(h -> assertThat(h).hasSize(64).matches("[0-9a-f]+"));
        }

        @Test
        @DisplayName("a debit and the equal negative signed amount hash the same")
        void debitEqualsNegativeSignedAmount() {
            BankStatementCreateRequest signed = request();
            signed.setTransactions(List.of(row("-50.00")));
            BankStatementCreateRequest debit = request();
            BankStatementCreateRequest.Transaction row = row("1.00");
            row.setSignedAmount(null);
            row.setDebit(amount("50"));
            debit.setTransactions(List.of(row));

            assertThat(BankStatementServiceImpl.hash(signed)).isEqualTo(BankStatementServiceImpl.hash(debit));
        }
    }

    // ---- listStatements -----------------------------------------------------------------------

    @Nested
    @DisplayName("listStatements")
    class ListStatements {

        @Test
        @DisplayName("from after to is a VALIDATION_ERROR on from, before any query")
        void invertedRange() {
            BankRecException refused = catchBankRec(() -> service.listStatements(null, END, START, 0, 20));

            assertThat(refused.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR);
            assertThat(refused.fieldErrors()).containsExactly(entry("from", "must not be after to"));
            verifyNoInteractions(statements);
        }

        @Test
        @DisplayName("an out-of-range page size is refused by the paging bounds")
        void pageBounds() {
            BankRecException refused = catchBankRec(() -> service.listStatements(null, null, null, 0, 0));

            assertThat(refused.fieldErrors()).containsKey("size");
            verifyNoInteractions(statements);
        }

        @Test
        @DisplayName("rows carry display values and counts; a statement without rows counts zero")
        void rows() {
            BankStatement september = statement(STATEMENT_ID, START, END, null);
            UUID octoberId = UUIDv7Generator.generate();
            BankStatement october = statement(octoberId, END.plusDays(1), END.plusDays(31), null);
            UUID unknownAccount = UUIDv7Generator.generate();
            october.setGlAccountId(unknownAccount);
            Pageable pageable = PageRequest.of(0, 2, Sort.by("startDate", "statementId"));
            when(statements.findAll(any(Specification.class), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(september, october), pageable, 5));
            when(bankCashAccounts.displayValues(List.of(ACCOUNT_ID, unknownAccount)))
                    .thenReturn(Map.of(ACCOUNT_ID, CASH));
            when(transactions.countByStatementIdIn(
                            List.of(STATEMENT_ID, octoberId), BankTransactionStatus.POSSIBLE_DUPLICATE))
                    .thenReturn(List.of(new StatementCounts(STATEMENT_ID, 4L, 2L)));

            BankStatementListResponse page = service.listStatements(null, null, null, 0, 2);

            assertThat(page.getTotalElements()).isEqualTo(5L);
            assertThat(page.getPageNumber()).isZero();
            assertThat(page.getPageSize()).isEqualTo(2);
            assertThat(page.getTotalPages()).isEqualTo(3);
            assertThat(page.getStatements()).hasSize(2);
            BankStatementResponse first = page.getStatements().get(0);
            assertThat(first.getStatementId()).isEqualTo(STATEMENT_ID);
            assertThat(first.getAccountCode()).isEqualTo("1000");
            assertThat(first.getBankTransactionCount()).isEqualTo(4L);
            assertThat(first.getPossibleDuplicateCount()).isEqualTo(2L);
            assertThat(first.getModifiedCount()).isNull();
            assertThat(first.getReconciliations()).isNull();
            BankStatementResponse second = page.getStatements().get(1);
            assertThat(second.getStatementId()).isEqualTo(octoberId);
            assertThat(second.getAccountCode()).isNull();
            assertThat(second.getBankTransactionCount()).isZero();
            assertThat(second.getPossibleDuplicateCount()).isZero();
        }

        @Test
        @DisplayName("an empty page answers without counting transactions")
        void emptyPage() {
            Pageable pageable = PageRequest.of(1, 20, Sort.by("startDate", "statementId"));
            when(statements.findAll(any(Specification.class), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(), pageable, 0));
            when(bankCashAccounts.displayValues(List.of())).thenReturn(Map.of());

            BankStatementListResponse page = service.listStatements(ACCOUNT_ID, START, END, 1, 20);

            assertThat(page.getStatements()).isEmpty();
            assertThat(page.getTotalElements()).isZero();
            assertThat(page.getPageNumber()).isEqualTo(1);
            verify(transactions, never()).countByStatementIdIn(anyCollection(), any());
        }

        @Test
        @DisplayName("the filter restricts account and overlaps the window: endDate >= from, startDate <= to")
        void everyFilter() {
            Filter filter = runSpecification(ACCOUNT_ID, START, END);

            verify(filter.cb).equal(filter.account, ACCOUNT_ID);
            verify(filter.cb).greaterThanOrEqualTo(filter.endDate, START);
            verify(filter.cb).lessThanOrEqualTo(filter.startDate, END);
            verify(filter.cb).and(filter.accountPredicate, filter.fromPredicate, filter.toPredicate);
        }

        @Test
        @DisplayName("an unfiltered list adds no predicate")
        void noFilter() {
            Filter filter = runSpecification(null, null, null);

            verify(filter.cb).and();
            verify(filter.cb, never()).equal(any(), any(Object.class));
            verify(filter.root, never()).get(anyString());
        }

        @Test
        @DisplayName("only the bounds that are given become predicates")
        void partialFilters() {
            Filter fromOnly = runSpecification(null, START, null);
            verify(fromOnly.cb).and(fromOnly.fromPredicate);

            Filter toOnly = runSpecification(null, null, END);
            verify(toOnly.cb).and(toOnly.toPredicate);
        }

        private Filter runSpecification(UUID glAccountId, LocalDate from, LocalDate to) {
            Filter filter = new Filter();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Specification<BankStatement>> spec = ArgumentCaptor.forClass(Specification.class);
            when(statements.findAll(spec.capture(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
            lenient().when(bankCashAccounts.displayValues(List.of())).thenReturn(Map.of());

            service.listStatements(glAccountId, from, to, 0, 20);

            spec.getValue().toPredicate(filter.root, filter.query, filter.cb);
            return filter;
        }
    }

    /** Criteria mocks for running the list specification. */
    @SuppressWarnings("unchecked")
    private static final class Filter {
        final Root<BankStatement> root = mock(Root.class);
        final CriteriaQuery<?> query = mock(CriteriaQuery.class);
        final CriteriaBuilder cb = mock(CriteriaBuilder.class);
        final Path<Object> account = mock(Path.class);
        final Path<LocalDate> endDate = mock(Path.class);
        final Path<LocalDate> startDate = mock(Path.class);
        final Predicate accountPredicate = mock(Predicate.class);
        final Predicate fromPredicate = mock(Predicate.class);
        final Predicate toPredicate = mock(Predicate.class);

        Filter() {
            lenient().when(root.get("glAccountId")).thenReturn(account);
            lenient().when(root.<LocalDate>get("endDate")).thenReturn(endDate);
            lenient().when(root.<LocalDate>get("startDate")).thenReturn(startDate);
            lenient().when(cb.equal(account, ACCOUNT_ID)).thenReturn(accountPredicate);
            lenient().when(cb.greaterThanOrEqualTo(endDate, START)).thenReturn(fromPredicate);
            lenient().when(cb.lessThanOrEqualTo(startDate, END)).thenReturn(toPredicate);
        }
    }

    // ---- getStatement -------------------------------------------------------------------------

    @Nested
    @DisplayName("getStatement")
    class GetStatement {

        @Test
        @DisplayName("an unknown statement is 404 BANK_STATEMENT_NOT_FOUND")
        void notFound() {
            UUID unknown = UUIDv7Generator.generate();
            when(statements.findById(unknown)).thenReturn(Optional.empty());

            BankRecException refused = catchBankRec(() -> service.getStatement(unknown));

            assertThat(refused.code()).isEqualTo(BankRecErrorCode.BANK_STATEMENT_NOT_FOUND);
            assertThat(refused).hasMessageContaining(unknown.toString());
        }

        @Test
        @DisplayName("a statement answers with its counts and reconciliations, not replayed, no modifiedCount")
        void found() {
            stubCommitted(ACK);
            when(reconciliations.findByStatementIdOrderByStatementStartDateAsc(STATEMENT_ID))
                    .thenReturn(List.of(
                            reconciliationWith(ReconciliationStatus.INVALIDATED),
                            reconciliationWith(ReconciliationStatus.IN_PROGRESS)));

            BankStatementResponse response = service.getStatement(STATEMENT_ID);

            assertThat(response.getStatementId()).isEqualTo(STATEMENT_ID);
            assertThat(response.getAccountName()).isEqualTo("Cash");
            assertThat(response.getOpeningBalance()).isEqualByComparingTo("1000");
            assertThat(response.getClosingBalance()).isEqualByComparingTo("1250");
            assertThat(response.getBankTransactionCount()).isEqualTo(3L);
            assertThat(response.getPossibleDuplicateCount()).isEqualTo(1L);
            assertThat(response.getModifiedCount()).isNull();
            assertThat(response.isReplayed()).isFalse();
            assertThat(response.getReconciliations())
                    .extracting(BankStatementResponse.ReconciliationLink::getStatus)
                    .containsExactly("INVALIDATED", "IN_PROGRESS");
        }

        @Test
        @DisplayName("a statement with no rows and an account the tenant no longer holds counts zero, no display")
        void noRowsNoAccount() {
            BankStatement stored = statement(STATEMENT_ID, START, END, null);
            when(statements.findById(STATEMENT_ID)).thenReturn(Optional.of(stored));
            when(bankCashAccounts.displayValues(List.of(ACCOUNT_ID))).thenReturn(Map.of());
            when(transactions.countByStatementIdIn(List.of(STATEMENT_ID), BankTransactionStatus.POSSIBLE_DUPLICATE))
                    .thenReturn(List.of());
            when(reconciliations.findByStatementIdOrderByStatementStartDateAsc(STATEMENT_ID))
                    .thenReturn(List.of());

            BankStatementResponse response = service.getStatement(STATEMENT_ID);

            assertThat(response.getAccountCode()).isNull();
            assertThat(response.getAccountName()).isNull();
            assertThat(response.getBankTransactionCount()).isZero();
            assertThat(response.getPossibleDuplicateCount()).isZero();
            assertThat(response.getReconciliations()).isEmpty();
        }
    }
}
