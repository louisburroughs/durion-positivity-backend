package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.BankRecCloseTestPolicy;
import com.positivity.accounting.internal.bankrec.dto.BankStatementCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.CloseReadinessAccount;
import com.positivity.accounting.internal.bankrec.dto.CloseReadinessCheck;
import com.positivity.accounting.internal.bankrec.dto.CloseReadinessResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchCreateRequest;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.BankRecClosePolicy;
import com.positivity.accounting.internal.bankrec.enums.ReadinessCheckCode;
import com.positivity.accounting.internal.bankrec.enums.ReadinessSeverity;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankReconciliationService;
import com.positivity.accounting.internal.bankrec.service.BankStatementService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationAdjustmentService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationApprovalService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationMatchingService;
import com.positivity.accounting.internal.dto.AccountingPeriodResponse;
import com.positivity.accounting.internal.dto.BankReconciliationExceptionRequest;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.PeriodCloseRequest;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.exception.PeriodBankReconciliationIncompleteException;
import com.positivity.accounting.internal.exception.PeriodCloseExceptionNotPermittedException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.service.AccountingPeriodService;
import com.positivity.accounting.internal.service.JournalEntryService;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Period-close readiness, the close policy and the exception path on the real baseline, through the real
 * statement, reconciliation, approval, posting and close paths (SPEC-manual-bank-reconciliation §5.2, §5.3, §5.6,
 * §5.9, §8.4; story S6, #2305). Each test works on accounts and months of its own, commits, and removes what it
 * wrote. While a test runs, every other reconcilable account of the tenant (the seeded {@code 1000 Cash}) is taken
 * out of close scope so readiness sees only the test's own bank account. Requires Docker.
 */
@DisplayName("Bank reconciliation close readiness, policy and exception on Postgres (#2305)")
class BankReconciliationCloseReadinessPostgresIT extends PostgresTenancyTestBase {

    private static final String ACK = "First statement reconciled on this account";
    private static final String PREPARER = "preparer";
    private static final String APPROVER = "controller";
    private static final String JUSTIFICATION = "September statement delayed by the bank";
    private static final String[] ALL = {
        "accounting:reconciliation:view", "accounting:reconciliation:adjust", "accounting:reconciliation:approve"
    };

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private JournalEntryService journalEntries;

    @Autowired
    private JournalEntryLineRepository lines;

    @Autowired
    private AccountingPeriodService periods;

    @Autowired
    private AccountingPeriodRepository periodRepository;

    @Autowired
    private AccountingConfigurationRepository configuration;

    @Autowired
    private AccountingAuditLogRepository auditLogs;

    @Autowired
    private BankStatementService statementService;

    @Autowired
    private BankTransactionRepository bankTransactions;

    @Autowired
    private BankReconciliationService reconciliationService;

    @Autowired
    private BankReconciliationRepository reconciliationRepository;

    @Autowired
    private ReconciliationMatchingService matching;

    @Autowired
    private ReconciliationApprovalService approval;

    @Autowired
    private ReconciliationAdjustmentService adjustmentService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<String> periodCodes = new ArrayList<>();
    private List<UUID> sidelined = List.of();

    @BeforeEach
    void isolateScope() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.update(
                "DELETE FROM accounting_configuration WHERE tenant_id = ? AND config_key LIKE 'BANK_REC_%'", TENANT_A);
        sidelined = owner.queryForList(
                "SELECT gl_account_id FROM gl_account WHERE tenant_id = ? AND reconcilable = TRUE",
                UUID.class,
                TENANT_A);
        for (UUID id : sidelined) {
            owner.update("UPDATE gl_account SET reconcilable = FALSE WHERE gl_account_id = ?", id);
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        for (UUID id : sidelined) {
            owner.update("UPDATE gl_account SET reconcilable = TRUE WHERE gl_account_id = ?", id);
        }
        owner.update(
                "DELETE FROM accounting_configuration WHERE tenant_id = ? AND config_key LIKE 'BANK_REC_%'", TENANT_A);
        for (String code : periodCodes) {
            owner.update("DELETE FROM accounting_period WHERE tenant_id = ? AND period_code = ?", TENANT_A, code);
        }
        periodCodes.clear();
        for (UUID account : accounts) {
            String recons = "SELECT reconciliation_id FROM bank_reconciliation WHERE gl_account_id = '" + account + "'";
            owner.update("DELETE FROM bank_reconciliation_adjustment WHERE reconciliation_id IN (" + recons + ")");
            owner.update("DELETE FROM bank_reconciliation_outstanding_item WHERE gl_account_id = ?", account);
            owner.update("DELETE FROM bank_reconciliation_gl_match WHERE reconciliation_id IN (" + recons + ")");
            owner.update("DELETE FROM bank_reconciliation_bank_match WHERE match_id IN (SELECT match_id FROM"
                    + " bank_reconciliation_match WHERE reconciliation_id IN (" + recons + "))");
            owner.update("UPDATE bank_reconciliation_match SET replaces_match_id = NULL WHERE reconciliation_id IN ("
                    + recons + ")");
            owner.update("DELETE FROM bank_reconciliation_match WHERE reconciliation_id IN (" + recons + ")");
            owner.update("DELETE FROM bank_reconciliation WHERE gl_account_id = ?", account);
            owner.update("DELETE FROM bank_transaction WHERE gl_account_id = ?", account);
            owner.update(
                    "UPDATE bank_statement SET superseded_by_statement_id = NULL WHERE gl_account_id = ?", account);
            owner.update("DELETE FROM bank_statement WHERE gl_account_id = ?", account);
            owner.update("DELETE FROM bank_account_profile WHERE gl_account_id = ?", account);
        }
        for (UUID account : accounts) {
            List<UUID> entries = owner.queryForList(
                    "SELECT DISTINCT journal_entry_id FROM journal_entry_line WHERE gl_account_id = ?",
                    UUID.class,
                    account);
            for (UUID entry : entries) {
                owner.update(
                        "UPDATE journal_entry SET reversal_journal_entry_id = NULL, reversed_by_journal_entry_id = NULL"
                                + " WHERE journal_entry_id = ?",
                        entry);
            }
            for (UUID entry : entries) {
                owner.update("DELETE FROM journal_entry_line WHERE journal_entry_id = ?", entry);
                owner.update("DELETE FROM journal_entry WHERE journal_entry_id = ?", entry);
            }
        }
        for (UUID account : accounts) {
            owner.update("DELETE FROM gl_account WHERE gl_account_id = ?", account);
        }
        accounts.clear();
    }

    // ---- AC 1 ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC1: a FINALIZED reconciliation reaching the period end is ready and closes under the default policy")
    void reconciledCloses() {
        UUID cash = bankAccount();
        reconciledMonth(cash, "2018-03");

        CloseReadinessResponse readiness = readiness("2018-03");
        assertThat(readiness.policy()).isEqualTo(BankRecClosePolicy.REQUIRED_WITH_EXCEPTION);
        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.blockingCount()).isZero();
        CloseReadinessAccount account = account(readiness, cash);
        assertThat(account.baselineDate()).isEqualTo(LocalDate.of(2018, 3, 1));
        assertThat(account.reconciledFrontier()).isEqualTo(LocalDate.of(2018, 3, 31));
        assertThat(account.checks()).isEmpty();

        as("accounting:period:close");
        AccountingPeriodResponse closed = close("2018-03", null);
        assertThat(closed.getStatus()).isEqualTo(AccountingPeriodStatus.CLOSED);
        assertThat(closed.getBankReconciliationReady()).isTrue();
        assertThat(closed.getBankReconciliationException()).isFalse();
        assertThat(periodOperations(closed.getPeriodId())).containsExactly("PERIOD_CLOSE");
    }

    // ---- AC 2, 3, 4 -----------------------------------------------------------------------------------

    @Test
    @DisplayName("AC2-4: in flight blocks; an exception without override is 403; with it the period closes, audited")
    void inFlightAndException() {
        UUID cash = bankAccount();
        inFlightMonth(cash, "2018-04");

        as("accounting:period:close");
        assertThatThrownBy(() -> close("2018-04", null))
                .isInstanceOfSatisfying(
                        PeriodBankReconciliationIncompleteException.class,
                        e -> assertThat(e.getUnreconciledAccounts())
                                .singleElement()
                                .satisfies(a -> {
                                    assertThat(a.glAccountId()).isEqualTo(cash);
                                    assertThat(a.checkCodes()).contains("RECONCILIATION_IN_FLIGHT");
                                }));

        assertThatThrownBy(() -> close("2018-04", exception(JUSTIFICATION)))
                .isInstanceOf(PeriodCloseExceptionNotPermittedException.class);
        assertThat(periodStatus("2018-04")).isEqualTo(AccountingPeriodStatus.OPEN);

        as("accounting:period:close", "accounting:period:override");
        AccountingPeriodResponse closed = close("2018-04", exception(JUSTIFICATION));
        assertThat(closed.getStatus()).isEqualTo(AccountingPeriodStatus.CLOSED);
        assertThat(closed.getBankReconciliationException()).isTrue();
        assertThat(closed.getBankReconciliationReady()).isFalse();
        List<AccountingAuditLog> rows = periodRows(closed.getPeriodId());
        assertThat(rows)
                .extracting(AccountingAuditLog::getOperation)
                .containsExactly("PERIOD_CLOSE_BANKREC_EXCEPTION", "PERIOD_CLOSE");
        assertThat(rows.getFirst().getJustification()).isEqualTo(JUSTIFICATION);
        assertThat(rows.getFirst().getOldValue()).contains("RECONCILIATION_IN_FLIGHT");
        assertThat(rows.get(1).getNewValue()).contains("unreconciled=").contains("RECONCILIATION_IN_FLIGHT");
    }

    // ---- AC 5, 6 ------------------------------------------------------------------------------------

    @Test
    @DisplayName("[M] AC5-6: REQUIRED refuses an exception; ADVISORY closes not ready and lists every check")
    void policyMatrix() {
        UUID cash = bankAccount();
        inFlightMonth(cash, "2018-05");

        setPolicy(BankRecClosePolicy.REQUIRED);
        as("accounting:period:close", "accounting:period:override");
        assertThatThrownBy(() -> close("2018-05", exception(JUSTIFICATION)))
                .isInstanceOfSatisfying(
                        PeriodBankReconciliationIncompleteException.class,
                        e -> assertThat(e.getRefusedExceptionReason()).contains("REQUIRED"));

        setPolicy(BankRecClosePolicy.ADVISORY);
        CloseReadinessResponse readiness = readiness("2018-05");
        assertThat(readiness.ready()).isTrue();
        assertThat(codes(account(readiness, cash).checks())).contains(ReadinessCheckCode.RECONCILIATION_IN_FLIGHT);
        AccountingPeriodResponse closed = close("2018-05", null);
        assertThat(closed.getStatus()).isEqualTo(AccountingPeriodStatus.CLOSED);
        assertThat(closed.getBankReconciliationReady()).isFalse();
        assertThat(closed.getBankReconciliationException()).isFalse();
    }

    // ---- AC 12 --------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC12: reopen, a posting invalidates the covering approval, the re-close is blocked")
    void reopenInvalidateReclose() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        UUID reconId = reconciledMonth(cash, "2018-06");
        as("accounting:period:close", "accounting:period:reopen");
        close("2018-06", null);
        inTx(() -> periods.reopenPeriod("2018-06", "Late supplier refund to book in June"));

        as(PREPARER);
        post(cash, revenue, "12.00", LocalDate.of(2018, 6, 20));
        assertThat(inTx(() ->
                        reconciliationRepository.findById(reconId).orElseThrow().getStatus()))
                .isEqualTo(ReconciliationStatus.INVALIDATED);

        CloseReadinessResponse readiness = readiness("2018-06");
        assertThat(codes(account(readiness, cash).checks()))
                .contains(ReadinessCheckCode.RECONCILIATION_INVALIDATED, ReadinessCheckCode.RECONCILIATION_APPROVED);
        as("accounting:period:close");
        assertThatThrownBy(() -> close("2018-06", null))
                .isInstanceOfSatisfying(
                        PeriodBankReconciliationIncompleteException.class,
                        e -> assertThat(e.getUnreconciledAccounts().getFirst().checkCodes())
                                .contains("RECONCILIATION_INVALIDATED"));
        assertThat(periodStatus("2018-06")).isEqualTo(AccountingPeriodStatus.OPEN);
    }

    // ---- AC 10 --------------------------------------------------------------------------------------

    @Test
    @DisplayName("[M] AC10: an unmatched line before the baseline is not unexplained; one after it is")
    void baselineLowerBound() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        UUID before = cashLine(post(cash, revenue, "7.00", LocalDate.of(2018, 8, 20)), cash);
        statement(cash, "2018-09-01", "2018-09-30", "0", "9.00", List.of("9.00"), LocalDate.of(2018, 9, 12));
        UUID after = cashLine(post(cash, revenue, "9.00", LocalDate.of(2018, 9, 12)), cash);

        CloseReadinessAccount account = account(readiness("2018-09"), cash);

        assertThat(account.baselineDate()).isEqualTo(LocalDate.of(2018, 9, 1));
        CloseReadinessCheck ledger = account.checks().stream()
                .filter(c -> c.code() == ReadinessCheckCode.UNEXPLAINED_LEDGER_LINES)
                .findFirst()
                .orElseThrow();
        assertThat(ledger.references()).containsEntry("count", 1);
        assertThat((List<Object>) ledger.references().get("glLineIds"))
                .containsExactly(after)
                .doesNotContain(before);
    }

    @Test
    @DisplayName("[M] UNEXPLAINED_BANK_TRANSACTIONS counts settlementState = POSTED rows only; PENDING is left out")
    void pendingBankRowsAreNotUnexplained() {
        UUID cash = bankAccount();
        UUID statementId = statement(
                cash, "2018-10-01", "2018-10-31", "0", "13.00", List.of("9.00", "4.00"), LocalDate.of(2018, 10, 5));
        periodCodes.add("2018-10");
        List<UUID> rows = bankRows(statementId);
        inTx(() -> {
            BankTransaction pending = bankTransactions.findById(rows.get(1)).orElseThrow();
            pending.setSettlementState(SettlementState.PENDING);
            return bankTransactions.save(pending);
        });

        CloseReadinessCheck bank = account(readiness("2018-10"), cash).checks().stream()
                .filter(c -> c.code() == ReadinessCheckCode.UNEXPLAINED_BANK_TRANSACTIONS)
                .findFirst()
                .orElseThrow();

        assertThat(bank.references()).containsEntry("count", 1);
        assertThat((List<Object>) bank.references().get("bankTransactionIds")).containsExactly(rows.get(0));
    }

    @Test
    @DisplayName(
            "[M] BALANCE_AGREEMENT: approval and readiness read one as-of (POSTED + REVERSED, end 23:59:59.999999)")
    void balanceAgreementSharesTheAsOf() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        LocalDate last = LocalDate.of(2018, 11, 30);
        // A deposit at the last microsecond of the window, and a reversed pair inside it.
        UUID deposit = cashLine(post(cash, revenue, "100.00", last.atTime(23, 59, 59, 999_999_000)), cash);
        UUID mistake = post(cash, revenue, "25.00", LocalDate.of(2018, 11, 10).atTime(12, 0));
        inTx(() -> journalEntries.reverseJournalEntry(
                mistake, "Posted to the wrong bank account", LocalDate.of(2018, 11, 12)));
        UUID statementId = statement(cash, "2018-11-01", "2018-11-30", "0", "100.00", List.of("100.00"), last);
        as(PREPARER);
        UUID reconId = create(cash, statementId);
        inTx(() -> matching.createMatch(
                reconId,
                ReconciliationMatchCreateRequest.builder()
                        .bankTransactionIds(bankRows(statementId))
                        .glLineIds(List.of(deposit))
                        .requestId(UUIDv7Generator.generate())
                        .build()));
        inTx(() -> approval.submit(reconId, null));
        as(APPROVER);
        inTx(() -> approval.approve(reconId, null));
        SecurityContextHolder.clearContext();

        CloseReadinessAccount account = account(readiness("2018-11"), cash);

        assertThat(codes(account.checks())).doesNotContain(ReadinessCheckCode.BALANCE_AGREEMENT);
        assertThat(account.checks()).isEmpty();
    }

    // ---- AC 11 --------------------------------------------------------------------------------------

    @Test
    @DisplayName("[M] AC11: an OTHER adjustment leaving 2360 away from zero warns once aged, never blocks")
    void clearingBalanceAging() {
        UUID cash = bankAccount();
        // The seeded OTHER -> 2360 mapping is effective from 2021 on.
        LocalDate fee = LocalDate.of(2022, 2, 10);
        UUID statementId = statement(cash, "2022-02-01", "2022-02-28", "0", "-45.67", List.of("-45.67"), fee);
        as(ALL);
        UUID reconId = create(cash, statementId);
        UUID feeRow = bankRows(statementId).getFirst();
        periodCodes.add("2022-02");
        UUID adjustmentId = inTx(() -> adjustmentService.addAdjustment(
                        reconId,
                        ReconciliationAdjustmentRequest.builder()
                                .type(BankAdjustmentType.OTHER)
                                .bankTransactionId(feeRow)
                                .amount(new BigDecimal("-45.67"))
                                .transactionDate(fee)
                                .justification("Unidentified bank debit held in suspense")
                                .requestId(UUIDv7Generator.generate())
                                .build()))
                .getAdjustmentId();

        CloseReadinessResponse fresh = readiness("2022-03");
        assertThat(codes(fresh.checks())).doesNotContain(ReadinessCheckCode.CLEARING_BALANCE_AGING);

        setPolicy(BankRecClosePolicy.REQUIRED);
        CloseReadinessResponse aged = readiness("2022-06");
        CloseReadinessCheck check = aged.checks().stream()
                .filter(c -> c.code() == ReadinessCheckCode.CLEARING_BALANCE_AGING)
                .findFirst()
                .orElseThrow();
        assertThat(check.severity()).isEqualTo(ReadinessSeverity.WARNING);
        assertThat(check.references()).containsEntry("accountCode", "2360");
        assertThat((List<Object>) check.references().get("adjustmentIds")).containsExactly(adjustmentId);
    }

    // ---- fixtures -----------------------------------------------------------------------------------

    /** A month with one deposit matched and the reconciliation FINALIZED; returns the reconciliation id. */
    private UUID reconciledMonth(UUID cash, String month) {
        UUID revenue = otherAccount();
        LocalDate start = LocalDate.parse(month + "-01");
        LocalDate day = start.plusDays(9);
        UUID deposit = cashLine(post(cash, revenue, "100.00", day), cash);
        UUID statementId = statement(
                cash,
                start.toString(),
                start.withDayOfMonth(start.lengthOfMonth()).toString(),
                "0",
                "100.00",
                List.of("100.00"),
                day);
        as(PREPARER);
        UUID reconId = create(cash, statementId);
        inTx(() -> matching.createMatch(
                reconId,
                ReconciliationMatchCreateRequest.builder()
                        .bankTransactionIds(bankRows(statementId))
                        .glLineIds(List.of(deposit))
                        .requestId(UUIDv7Generator.generate())
                        .build()));
        inTx(() -> approval.submit(reconId, null));
        as(APPROVER);
        inTx(() -> approval.approve(reconId, null));
        SecurityContextHolder.clearContext();
        periodCodes.add(month);
        return reconId;
    }

    /** A month whose statement has an IN_PROGRESS reconciliation. */
    private void inFlightMonth(UUID cash, String month) {
        LocalDate start = LocalDate.parse(month + "-01");
        UUID statementId = statement(
                cash,
                start.toString(),
                start.withDayOfMonth(start.lengthOfMonth()).toString(),
                "0",
                "5.00",
                List.of("5.00"),
                start);
        as(PREPARER);
        create(cash, statementId);
        SecurityContextHolder.clearContext();
        periodCodes.add(month);
    }

    private void setPolicy(BankRecClosePolicy policy) {
        inTx(() -> {
            BankRecCloseTestPolicy.set(configuration, policy);
            return null;
        });
    }

    private CloseReadinessResponse readiness(String month) {
        return inTx(() -> periods.getCloseReadiness(month));
    }

    private AccountingPeriodResponse close(String month, PeriodCloseRequest request) {
        if (!periodCodes.contains(month)) {
            periodCodes.add(month);
        }
        return inTx(() -> periods.closePeriod(month, request));
    }

    private static PeriodCloseRequest exception(String justification) {
        return PeriodCloseRequest.builder()
                .bankReconciliationException(BankReconciliationExceptionRequest.builder()
                        .justification(justification)
                        .build())
                .build();
    }

    private AccountingPeriodStatus periodStatus(String month) {
        return inTx(() ->
                periodRepository.findByPeriodCode(month).map(p -> p.getStatus()).orElse(AccountingPeriodStatus.OPEN));
    }

    private List<AccountingAuditLog> periodRows(UUID periodId) {
        return inTx(() -> auditLogs.findByEntityTypeAndEntityIdOrderByTimestampAsc("ACCOUNTING_PERIOD", periodId));
    }

    private List<String> periodOperations(UUID periodId) {
        return periodRows(periodId).stream()
                .map(AccountingAuditLog::getOperation)
                .toList();
    }

    private static CloseReadinessAccount account(CloseReadinessResponse readiness, UUID glAccountId) {
        return readiness.accounts().stream()
                .filter(a -> a.glAccountId().equals(glAccountId))
                .findFirst()
                .orElseThrow();
    }

    private static List<ReadinessCheckCode> codes(List<CloseReadinessCheck> checks) {
        return checks.stream().map(CloseReadinessCheck::code).toList();
    }

    private static UsernamePasswordAuthenticationToken token(String user, String... authorities) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                user,
                null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, user));
        return token;
    }

    /** Acts as {@code user} with every reconciliation permission; a permission list acts as the preparer. */
    private void as(String... userOrAuthorities) {
        if (userOrAuthorities.length == 1 && !userOrAuthorities[0].contains(":")) {
            SecurityContextHolder.getContext().setAuthentication(token(userOrAuthorities[0], ALL));
        } else {
            SecurityContextHolder.getContext().setAuthentication(token(PREPARER, userOrAuthorities));
        }
    }

    private UUID create(UUID cash, UUID statementId) {
        return inTx(() -> reconciliationService.create(ReconciliationCreateRequest.builder()
                        .glAccountId(cash)
                        .requestId(UUIDv7Generator.generate())
                        .statementId(statementId)
                        .build()))
                .getReconciliationId();
    }

    private UUID statement(
            UUID account,
            String start,
            String end,
            String opening,
            String closing,
            List<String> amounts,
            LocalDate rowDate) {
        List<BankStatementCreateRequest.Transaction> rows = new ArrayList<>();
        int n = 0;
        for (String amount : amounts) {
            rows.add(BankStatementCreateRequest.Transaction.builder()
                    .date(rowDate)
                    .signedAmount(new BigDecimal(amount))
                    .description("ROW " + (++n))
                    .build());
        }
        return inTx(() -> statementService
                .createManualStatement(BankStatementCreateRequest.builder()
                        .glAccountId(account)
                        .requestId(UUIDv7Generator.generate())
                        .statement(BankStatementCreateRequest.Header.builder()
                                .startDate(LocalDate.parse(start))
                                .endDate(LocalDate.parse(end))
                                .openingBalance(new BigDecimal(opening))
                                .closingBalance(new BigDecimal(closing))
                                .build())
                        .transactions(rows)
                        .gapAcknowledgement(ACK)
                        .build())
                .getStatementId());
    }

    private List<UUID> bankRows(UUID statementId) {
        return inTx(() -> bankTransactions.findByStatementIdOrderBySourceRowNumberAsc(statementId).stream()
                .map(BankTransaction::getBankTransactionId)
                .toList());
    }

    private UUID bankAccount() {
        return account(AccountType.ASSET, AccountSubtype.BANK_CASH, true);
    }

    private UUID otherAccount() {
        return account(AccountType.REVENUE, null, false);
    }

    private UUID account(AccountType type, AccountSubtype subtype, boolean reconcilable) {
        String suffix = UUIDv7Generator.generate().toString().substring(24);
        UUID id = inTx(() -> {
            GLAccount account = new GLAccount();
            account.setGlAccountId(UUIDv7Generator.generate());
            account.setAccountCode("R" + suffix);
            account.setAccountName("Readiness IT " + suffix);
            account.setAccountType(type);
            account.setAccountSubtype(subtype);
            account.setReconcilable(reconcilable);
            account.setActivationDate(LocalDateTime.of(2015, 1, 1, 0, 0));
            account.setCreatedBy("it");
            account.setModifiedBy("it");
            return glAccounts.save(account).getGlAccountId();
        });
        accounts.add(id);
        return id;
    }

    private static JournalEntryCreateRequest.JournalEntryLineRequest line(UUID account, String debit, String credit) {
        return JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                .glAccountId(account)
                .debitAmount(new BigDecimal(debit))
                .creditAmount(new BigDecimal(credit))
                .build();
    }

    /** Posts Dr {@code debit} / Cr {@code credit} of {@code amount} on {@code day}; returns the entry id. */
    private UUID post(UUID debit, UUID credit, String amount, LocalDate day) {
        return post(debit, credit, amount, day.atTime(12, 0));
    }

    /** Posts Dr {@code debit} / Cr {@code credit} of {@code amount} at {@code at}; returns the entry id. */
    private UUID post(UUID debit, UUID credit, String amount, LocalDateTime at) {
        LocalDate day = at.toLocalDate();
        String month = YearMonth.from(day).toString();
        if (!periodCodes.contains(month)) {
            periodCodes.add(month);
        }
        return inTx(() -> {
            UUID created = journalEntries
                    .createJournalEntry(JournalEntryCreateRequest.builder()
                            .transactionDate(at)
                            .sourceEventId(UUIDv7Generator.generate())
                            .description("Readiness IT")
                            .lines(List.of(line(debit, amount, "0"), line(credit, "0", amount)))
                            .build())
                    .getJournalEntryId();
            return journalEntries.postJournalEntry(created, null).getJournalEntryId();
        });
    }

    private UUID cashLine(UUID entryId, UUID cash) {
        return inTx(() -> lines.findByJournalEntry_JournalEntryId(entryId).stream()
                .filter(l -> cash.equals(l.getGlAccountId()))
                .map(JournalEntryLine::getLineId)
                .findFirst()
                .orElseThrow());
    }

    private <T> T inTx(Callable<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return asTenant(
                TENANT_A,
                () -> tx.execute(status -> {
                    try {
                        return work.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
    }
}
