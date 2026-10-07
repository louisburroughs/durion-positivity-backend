package com.positivity.accounting.internal.bankrec.readmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.CloseReadinessAccount;
import com.positivity.accounting.internal.bankrec.dto.CloseReadinessCheck;
import com.positivity.accounting.internal.bankrec.dto.CloseReadinessResponse;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.AdjustmentStatus;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.BankRecClosePolicy;
import com.positivity.accounting.internal.bankrec.enums.BankRecCloseScope;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReadinessCheckCode;
import com.positivity.accounting.internal.bankrec.enums.ReadinessSeverity;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.IncompleteImportLookup;
import com.positivity.accounting.internal.bankrec.repository.AccountDate;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import com.positivity.accounting.internal.bankrec.service.BankRecPolicy;
import com.positivity.accounting.internal.bankrec.service.BankRecSettings;
import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.bankrec.service.LedgerEntries;
import com.positivity.accounting.internal.bankrec.service.LedgerLine;
import com.positivity.accounting.internal.bankrec.service.ReconciliationCalculator;
import com.positivity.accounting.internal.bankrec.service.ReconciliationCalculator.Unexplained;
import com.positivity.accounting.internal.bankrec.service.ReconciliationLedger;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.BankReconciliationExceptionRequest;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.exception.PeriodBankReconciliationIncompleteException;
import com.positivity.accounting.internal.exception.PeriodCloseExceptionNotPermittedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The close-readiness read model and the close decision (SPEC-manual-bank-reconciliation §5.2, §5.3, §5.8, §8.4;
 * story S6, #2305): each check against a fixture of its state, the frontier and baseline rules, the clearing-account
 * rule, and the policy matrix. [M] marks the mutation guards §8.4 names.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BankReconciliationCloseReadiness (#2305)")
class BankReconciliationCloseReadinessTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID CASH = UUID.fromString("01936e5e-0000-7000-8000-000000001000");
    private static final UUID CLEARING = UUID.fromString("01936e5e-0000-7000-8000-000000002360");
    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 31);

    @Mock
    private BankRecPolicy policy;

    @Mock
    private BankCashAccounts bankCashAccounts;

    @Mock
    private BankStatementRepository statements;

    @Mock
    private BankReconciliationRepository reconciliations;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private BankReconciliationOutstandingItemRepository outstandingItems;

    @Mock
    private BankReconciliationAdjustmentRepository adjustments;

    @Mock
    private ReconciliationCalculator calculator;

    @Mock
    private ReconciliationLedger ledger;

    @Mock
    private LedgerEntries ledgerEntries;

    @Mock
    private ObjectProvider<IncompleteImportLookup> importLookups;

    @Mock
    private IncompleteImportLookup importLookup;

    private BankReconciliationCloseReadiness service;
    private AccountingPeriod period;

    @BeforeEach
    void setUp() {
        service = new BankReconciliationCloseReadiness(
                com.positivity.accounting.internal.service.TestZoneResolvers.utc(CLOCK),
                policy,
                BankRecSettings.defaults(),
                new FunctionalCurrency(new LedgerCurrency("USD")),
                bankCashAccounts,
                statements,
                reconciliations,
                transactions,
                outstandingItems,
                adjustments,
                calculator,
                ledger,
                ledgerEntries,
                importLookups);
        period = new AccountingPeriod();
        period.setPeriodId(UUID.randomUUID());
        period.setPeriodCode("2026-08");
        period.setStartDate(START);
        period.setEndDate(END);
        period.setStatus(AccountingPeriodStatus.OPEN);

        policy(BankRecClosePolicy.REQUIRED_WITH_EXCEPTION, 0);
        when(bankCashAccounts.listInScope(any())).thenReturn(List.of(new BankCashAccount(CASH, "1000", "Cash")));
        when(importLookups.orderedStream()).thenAnswer(inv -> Stream.of(importLookup));
        when(importLookup.incompleteImportIds(any(), any())).thenReturn(List.of());
        when(calculator.unexplained(any(), any(), any(), any()))
                .thenReturn(new Unexplained(List.of(), List.of(), List.of()));
        when(ledger.balanceAsOf(any(), any())).thenReturn(BigDecimal.ZERO);
        when(ledgerEntries.draftEntryIds(any(), any())).thenReturn(List.of());
        when(ledgerEntries.statuses(any())).thenReturn(Map.of());
        when(reconciliations.findIntersecting(any(), any(), any(), any())).thenReturn(List.of());
        when(reconciliations
                        .findByGlAccount_GlAccountIdAndStatusInAndStatementEndDateLessThanEqualOrderByStatementStartDateAsc(
                                any(), anyCollection(), any()))
                .thenReturn(List.of());
        when(adjustments.findAllOfType(any())).thenReturn(List.of());
        when(adjustments.findAllOnAccount(any())).thenReturn(List.of());
        when(outstandingItems.findByGlAccountIdAndItemDateLessThanEqual(any(), any()))
                .thenReturn(List.of());
        when(transactions.findByGlAccountIdAndArrivedAfterApprovalTrueAndTransactionDateBetweenAndStatusIn(
                        any(), any(), any(), anyCollection()))
                .thenReturn(List.of());
        // The default fixture: baseline 2026-06-01, statements to 08-31, one FINALIZED window 06-01..08-31.
        baseline(LocalDate.of(2026, 6, 1));
        coverage(END);
        finalized(recon(LocalDate.of(2026, 6, 1), END, "500.00"));
        when(ledger.balanceAsOf(CASH, END)).thenReturn(new BigDecimal("500.00"));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ---------------------------------------------------------------------------------------

    private void policy(BankRecClosePolicy closePolicy, int lag) {
        when(policy.settings())
                .thenReturn(
                        new BankRecPolicy.Settings(closePolicy, BankRecCloseScope.BANK_CASH_SUBTYPE, lag, false, null));
    }

    private void baseline(LocalDate start) {
        BankStatement statement = new BankStatement();
        statement.setStatementId(UUID.randomUUID());
        statement.setStartDate(start);
        when(statements
                        .findFirstByGlAccountIdAndStatusAndGapAcknowledgementIsNotNullAndStartDateLessThanEqualOrderByStartDateDesc(
                                CASH, BankStatementStatus.COMMITTED, END))
                .thenReturn(Optional.ofNullable(start == null ? null : statement));
    }

    private void noBaseline() {
        when(statements
                        .findFirstByGlAccountIdAndStatusAndGapAcknowledgementIsNotNullAndStartDateLessThanEqualOrderByStartDateDesc(
                                CASH, BankStatementStatus.COMMITTED, END))
                .thenReturn(Optional.empty());
    }

    private void coverage(LocalDate frontier) {
        when(statements.findLatestEndDateByGlAccountIdInStartingOnOrBefore(
                        any(), eq(BankStatementStatus.COMMITTED), eq(END)))
                .thenReturn(frontier == null ? List.of() : List.of(new AccountDate(CASH, frontier)));
    }

    private void finalized(BankReconciliation... rows) {
        when(reconciliations.findByGlAccount_GlAccountIdAndStatusOrderByStatementStartDateAsc(
                        CASH, ReconciliationStatus.FINALIZED))
                .thenReturn(List.of(rows));
    }

    private static BankReconciliation recon(LocalDate start, LocalDate end, String approved) {
        BankReconciliation recon = new BankReconciliation(UUID.randomUUID());
        recon.setGlAccount(new GLAccount(CASH));
        recon.setStatementStartDate(start);
        recon.setStatementEndDate(end);
        recon.setStatus(ReconciliationStatus.FINALIZED);
        recon.setApprovedGlEndingBalance(approved == null ? null : new BigDecimal(approved));
        recon.setFinalizedAt(Instant.parse("2026-09-05T10:00:00Z"));
        return recon;
    }

    private static BankReconciliationExceptionRequest exception(String justification) {
        return BankReconciliationExceptionRequest.builder()
                .justification(justification)
                .build();
    }

    private CloseReadinessAccount cash(CloseReadinessResponse readiness) {
        return readiness.accounts().getFirst();
    }

    private static List<ReadinessCheckCode> codes(List<CloseReadinessCheck> checks) {
        return checks.stream().map(CloseReadinessCheck::code).toList();
    }

    private static void authorities(String... authorities) {
        TestingAuthenticationToken token = new TestingAuthenticationToken("closer", null, authorities);
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // ---- per-account checks -----------------------------------------------------------------------------

    @Test
    @DisplayName("AC1: a FINALIZED chain reaching the period end and nothing unexplained is ready")
    void readyWhenReconciledToPeriodEnd() {
        CloseReadinessResponse readiness = service.evaluate(period);

        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.blockingCount()).isZero();
        assertThat(readiness.policy()).isEqualTo(BankRecClosePolicy.REQUIRED_WITH_EXCEPTION);
        CloseReadinessAccount cash = cash(readiness);
        assertThat(cash.accountCode()).isEqualTo("1000");
        assertThat(cash.baselineDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(cash.coverageFrontier()).isEqualTo(END);
        assertThat(cash.reconciledFrontier()).isEqualTo(END);
        assertThat(cash.checks()).isEmpty();
    }

    @Test
    @DisplayName("AC2: an IN_PROGRESS reconciliation ending inside the period is RECONCILIATION_IN_FLIGHT")
    void inFlightBlocks() {
        BankReconciliation inProgress = recon(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15), null);
        inProgress.setStatus(ReconciliationStatus.IN_PROGRESS);
        when(reconciliations
                        .findByGlAccount_GlAccountIdAndStatusInAndStatementEndDateLessThanEqualOrderByStatementStartDateAsc(
                                eq(CASH), anyCollection(), eq(END)))
                .thenReturn(List.of(inProgress));

        CloseReadinessResponse readiness = service.evaluate(period);

        assertThat(readiness.ready()).isFalse();
        assertThat(codes(cash(readiness).checks())).containsExactly(ReadinessCheckCode.RECONCILIATION_IN_FLIGHT);
        assertThat(cash(readiness).checks().getFirst().references())
                .containsEntry("reconciliationIds", List.of(inProgress.getReconciliationId()));
    }

    @Test
    @DisplayName("a broken chain stops the frontier at the gap: RECONCILIATION_APPROVED blocks")
    void brokenChainStopsFrontier() {
        finalized(
                recon(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "100.00"),
                recon(LocalDate.of(2026, 8, 1), END, "500.00"));

        CloseReadinessAccount cash = cash(service.evaluate(period));

        assertThat(cash.reconciledFrontier()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(codes(cash.checks())).contains(ReadinessCheckCode.RECONCILIATION_APPROVED);
    }

    @Test
    @DisplayName("a chain from a moved baseline: windows before the baseline that applies at period end are skipped")
    void chainFromMovedBaseline() {
        baseline(LocalDate.of(2026, 7, 1));
        finalized(
                recon(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "50.00"),
                recon(LocalDate.of(2026, 7, 1), END, "500.00"));

        CloseReadinessAccount cash = cash(service.evaluate(period));

        assertThat(cash.baselineDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(cash.reconciledFrontier()).isEqualTo(END);
        assertThat(cash.checks()).isEmpty();
    }

    @Test
    @DisplayName("[M] coverage lag moves the thresholds back and reports COVERAGE_LAG_APPLIED")
    void coverageLag() {
        finalized(recon(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 31), "400.00"));
        when(ledger.balanceAsOf(CASH, LocalDate.of(2026, 7, 31))).thenReturn(new BigDecimal("400.00"));
        coverage(LocalDate.of(2026, 8, 14));

        CloseReadinessAccount withoutLag = cash(service.evaluate(period));
        assertThat(codes(withoutLag.checks()))
                .containsExactly(ReadinessCheckCode.STATEMENT_COVERAGE, ReadinessCheckCode.RECONCILIATION_APPROVED);

        policy(BankRecClosePolicy.REQUIRED_WITH_EXCEPTION, 31);
        CloseReadinessResponse withLag = service.evaluate(period);
        assertThat(withLag.ready()).isTrue();
        assertThat(codes(cash(withLag).checks())).containsExactly(ReadinessCheckCode.COVERAGE_LAG_APPLIED);
        assertThat(cash(withLag).checks().getFirst().references()).containsEntry("lagDays", 31);
        assertThat(withLag.warningCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC7: a covering approval that no longer agrees with the live balance is BALANCE_AGREEMENT")
    void balanceAgreement() {
        when(ledger.balanceAsOf(CASH, END)).thenReturn(new BigDecimal("512.34"));

        CloseReadinessAccount cash = cash(service.evaluate(period));

        assertThat(codes(cash.checks())).containsExactly(ReadinessCheckCode.BALANCE_AGREEMENT);
        assertThat(cash.checks().getFirst().references())
                .containsEntry("approvedGlEndingBalance", new BigDecimal("500.00"))
                .containsEntry("liveGlBalance", new BigDecimal("512.34"));
    }

    @Test
    @DisplayName("[M] BALANCE_AGREEMENT is exact: 0.0001 apart disagrees, a different scale of the same value agrees")
    void balanceAgreementIsExact() {
        when(ledger.balanceAsOf(CASH, END)).thenReturn(new BigDecimal("500.0001"));
        assertThat(codes(cash(service.evaluate(period)).checks()))
                .containsExactly(ReadinessCheckCode.BALANCE_AGREEMENT);

        when(ledger.balanceAsOf(CASH, END)).thenReturn(new BigDecimal("500.0000"));
        assertThat(cash(service.evaluate(period)).checks()).isEmpty();
    }

    @Test
    @DisplayName("[M] AC10: UNEXPLAINED_* run from the baseline that applies at the period end, with the covering id")
    void unexplainedFromApplyingBaseline() {
        BankTransaction row = new BankTransaction();
        row.setBankTransactionId(UUID.randomUUID());
        row.setSignedAmount(new BigDecimal("-12.00"));
        LedgerLine line = new LedgerLine(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "JE-1",
                LocalDate.of(2026, 8, 3),
                new BigDecimal("30.00"),
                null,
                null,
                false);
        when(calculator.unexplained(any(), any(), any(), any()))
                .thenReturn(new Unexplained(List.of(row), List.of(line), List.of()));

        CloseReadinessResponse readiness = service.evaluate(period);

        verify(calculator).unexplained(eq(CASH), eq(LocalDate.of(2026, 6, 1)), eq(END), any());
        assertThat(codes(cash(readiness).checks()))
                .containsExactly(
                        ReadinessCheckCode.UNEXPLAINED_BANK_TRANSACTIONS, ReadinessCheckCode.UNEXPLAINED_LEDGER_LINES);
        assertThat(cash(readiness).checks().get(0).references())
                .containsEntry("count", 1)
                .containsEntry("sum", new BigDecimal("-12.00"))
                .containsEntry("bankTransactionIds", List.of(row.getBankTransactionId()));
        assertThat(cash(readiness).checks().get(1).references()).containsEntry("glLineIds", List.of(line.lineId()));
    }

    @Test
    @DisplayName("AC10: an account without any statement is reported by STATEMENT_COVERAGE only")
    void noStatementOnlyCoverage() {
        noBaseline();
        coverage(null);
        finalized();

        CloseReadinessAccount cash = cash(service.evaluate(period));

        assertThat(cash.baselineDate()).isNull();
        assertThat(codes(cash.checks())).containsExactly(ReadinessCheckCode.STATEMENT_COVERAGE);
        verify(calculator, never()).unexplained(any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC8: OPEN items are listed with their sum; one older than 90 days adds only a WARNING")
    void outstandingItemsListedAndAged() {
        BankReconciliationOutstandingItem check = item(LocalDate.of(2026, 8, 28), "-120.00");
        BankReconciliationOutstandingItem stale = item(LocalDate.of(2026, 5, 1), "-80.00");
        BankReconciliationOutstandingItem cleared = item(LocalDate.of(2026, 8, 1), "-5.00");
        cleared.setStatus(OutstandingItemStatus.CLEARED);
        when(outstandingItems.findByGlAccountIdAndItemDateLessThanEqual(CASH, END))
                .thenReturn(List.of(check, stale, cleared));

        CloseReadinessResponse readiness = service.evaluate(period);

        CloseReadinessAccount cash = cash(readiness);
        assertThat(cash.openOutstandingItems()).hasSize(2);
        assertThat(cash.openOutstandingItemSum()).isEqualByComparingTo("-200.00");
        assertThat(codes(cash.checks())).containsExactly(ReadinessCheckCode.OUTSTANDING_ITEMS_AGING);
        assertThat(cash.checks().getFirst().references())
                .containsEntry("outstandingItemIds", List.of(stale.getOutstandingItemId()));
        assertThat(readiness.ready()).isTrue();
    }

    @Test
    @DisplayName("#2558: at 2026-01-31T23:30-06:00 an item dated Jan 31 is 0 days old in a Chicago calendar (aging"
            + " cap is the tenant's today, not the UTC clock's Feb 1)")
    void agingCapIsTheTenantCalendarToday() {
        Clock utc = Clock.fixed(
                com.positivity.accounting.internal.service.TestZoneResolvers.JAN_31_2330_CHICAGO, ZoneOffset.UTC);
        BankReconciliationCloseReadiness chicago = new BankReconciliationCloseReadiness(
                com.positivity.accounting.internal.service.TestZoneResolvers.fixed(
                        com.positivity.accounting.internal.service.TestZoneResolvers.CHICAGO, utc),
                policy,
                BankRecSettings.defaults(),
                new FunctionalCurrency(new LedgerCurrency("USD")),
                bankCashAccounts,
                statements,
                reconciliations,
                transactions,
                outstandingItems,
                adjustments,
                calculator,
                ledger,
                ledgerEntries,
                importLookups);
        AccountingPeriod february = new AccountingPeriod();
        february.setPeriodId(UUID.randomUUID());
        february.setPeriodCode("2026-02");
        february.setStartDate(LocalDate.of(2026, 2, 1));
        february.setEndDate(LocalDate.of(2026, 2, 28));
        february.setStatus(AccountingPeriodStatus.OPEN);
        when(outstandingItems.findByGlAccountIdAndItemDateLessThanEqual(eq(CASH), any()))
                .thenReturn(List.of(item(LocalDate.of(2026, 1, 31), "-10.00")));

        CloseReadinessAccount cash = cash(chicago.evaluate(february));

        assertThat(cash.openOutstandingItems())
                .singleElement()
                .extracting(i -> i.ageDays())
                .isEqualTo(0L);
    }

    private static BankReconciliationOutstandingItem item(LocalDate date, String amount) {
        BankReconciliationOutstandingItem item = new BankReconciliationOutstandingItem();
        item.setOutstandingItemId(UUID.randomUUID());
        item.setGlAccountId(CASH);
        item.setSide(OutstandingItemSide.LEDGER);
        item.setItemKind(OutstandingItemKind.OUTSTANDING_CHECK);
        item.setItemDate(date);
        item.setSignedAmount(new BigDecimal(amount));
        item.setStatus(OutstandingItemStatus.OPEN);
        return item;
    }

    @Test
    @DisplayName("AC12: an INVALIDATED reconciliation intersecting the period blocks with its reason")
    void invalidatedBlocks() {
        BankReconciliation invalidated = recon(LocalDate.of(2026, 8, 1), END, "500.00");
        invalidated.setStatus(ReconciliationStatus.INVALIDATED);
        invalidated.setInvalidationReason("LEDGER_LINE_POSTED");
        when(reconciliations.findIntersecting(CASH, ReconciliationStatus.INVALIDATED, START, END))
                .thenReturn(List.of(invalidated));

        CloseReadinessAccount cash = cash(service.evaluate(period));

        assertThat(codes(cash.checks())).contains(ReadinessCheckCode.RECONCILIATION_INVALIDATED);
        assertThat(cash.checks().stream()
                        .filter(c -> c.code() == ReadinessCheckCode.RECONCILIATION_INVALIDATED)
                        .findFirst()
                        .orElseThrow()
                        .references())
                .containsEntry("invalidationReasons", List.of("LEDGER_LINE_POSTED"));
    }

    @Test
    @DisplayName("a covering approval after closedAt is RECONCILED_AFTER_CLOSE (INFO, never blocks)")
    void reconciledAfterClose() {
        period.setClosedAt(Instant.parse("2026-09-01T00:00:00Z"));

        CloseReadinessResponse readiness = service.evaluate(period);

        assertThat(codes(cash(readiness).checks())).containsExactly(ReadinessCheckCode.RECONCILED_AFTER_CLOSE);
        assertThat(cash(readiness).checks().getFirst().severity()).isEqualTo(ReadinessSeverity.INFO);
        assertThat(readiness.ready()).isTrue();
    }

    @Test
    @DisplayName("INCOMPLETE_IMPORTS and UNPOSTED_ADJUSTMENTS are reported from their sources")
    void importsAndUnpostedAdjustments() {
        UUID importId = UUID.randomUUID();
        when(importLookup.incompleteImportIds(CASH, END)).thenReturn(List.of(importId));
        BankReconciliationAdjustment orphan = new BankReconciliationAdjustment();
        orphan.setAdjustmentId(UUID.randomUUID());
        orphan.setStatus(AdjustmentStatus.POSTED);
        orphan.setTransactionDate(LocalDate.of(2026, 8, 20));
        when(adjustments.findAllOnAccount(CASH)).thenReturn(List.of(orphan));

        CloseReadinessAccount cash = cash(service.evaluate(period));

        assertThat(codes(cash.checks()))
                .containsExactly(ReadinessCheckCode.UNPOSTED_ADJUSTMENTS, ReadinessCheckCode.INCOMPLETE_IMPORTS);
    }

    @Test
    @DisplayName("AC13: the scope setting decides which accounts are listed")
    void scopeFromPolicy() {
        when(policy.settings())
                .thenReturn(new BankRecPolicy.Settings(
                        BankRecClosePolicy.REQUIRED, BankRecCloseScope.ALL_RECONCILABLE, 0, false, null));

        service.evaluate(period);

        verify(bankCashAccounts).listInScope(BankRecCloseScope.ALL_RECONCILABLE);
    }

    // ---- tenant-wide ------------------------------------------------------------------------------------

    @Test
    @DisplayName("[M] a FINALIZED window starting after the period is no frontier: the period stays unreconciled")
    void laterWindowDoesNotReconcileAnEarlierPeriod() {
        noBaseline();
        coverage(END);
        finalized(recon(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "500.00"));

        CloseReadinessResponse readiness = service.evaluate(period);

        CloseReadinessAccount cash = cash(readiness);
        assertThat(cash.reconciledFrontier()).isNull();
        assertThat(codes(cash.checks())).containsExactly(ReadinessCheckCode.RECONCILIATION_APPROVED);
        assertThat(readiness.ready()).isFalse();
    }

    @Nested
    @DisplayName("CLEARING_BALANCE_AGING (AC11)")
    class ClearingBalanceAging {

        private BankReconciliationAdjustment other;

        @BeforeEach
        void otherAdjustment() {
            other = new BankReconciliationAdjustment();
            other.setAdjustmentId(UUID.randomUUID());
            other.setAdjustmentType(BankAdjustmentType.OTHER);
            other.setStatus(AdjustmentStatus.POSTED);
            other.setJournalEntryId(UUID.randomUUID());
            other.setTransactionDate(LocalDate.of(2026, 5, 20));
            other.setReconciliation(recon(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "0"));
            when(adjustments.findAllOfType(BankAdjustmentType.OTHER)).thenReturn(List.of(other));
            // Read from the entry's lines: the bank side and the counter account the entry actually hit.
            when(ledgerEntries.accountsOf(any())).thenReturn(Map.of(other.getJournalEntryId(), Set.of(CASH, CLEARING)));
            when(bankCashAccounts.displayValues(any()))
                    .thenReturn(
                            Map.of(CLEARING, new BankCashAccount(CLEARING, "2360", "Bank reconciliation clearing")));
        }

        @Test
        @DisplayName("away from zero at both dates: WARNING listing the adjustment, never blocking")
        void agedBalanceWarns() {
            when(ledger.balanceAsOf(CLEARING, END)).thenReturn(new BigDecimal("-45.67"));
            when(ledger.balanceAsOf(CLEARING, END.minusDays(90))).thenReturn(new BigDecimal("-45.67"));
            policy(BankRecClosePolicy.REQUIRED, 0);

            CloseReadinessResponse readiness = service.evaluate(period);

            assertThat(codes(readiness.checks())).containsExactly(ReadinessCheckCode.CLEARING_BALANCE_AGING);
            CloseReadinessCheck check = readiness.checks().getFirst();
            assertThat(check.severity()).isEqualTo(ReadinessSeverity.WARNING);
            assertThat(check.references())
                    .containsEntry("accountCode", "2360")
                    .containsEntry("balanceAtPeriodEnd", new BigDecimal("-45.67"))
                    .containsEntry("adjustmentIds", List.of(other.getAdjustmentId()));
            assertThat(readiness.ready()).isTrue();
        }

        @Test
        @DisplayName("[M] non-zero only at the period end (a fresh adjustment): no warning")
        void freshBalanceDoesNotWarn() {
            when(ledger.balanceAsOf(CLEARING, END)).thenReturn(new BigDecimal("-45.67"));
            when(ledger.balanceAsOf(CLEARING, END.minusDays(90))).thenReturn(BigDecimal.ZERO);

            assertThat(service.evaluate(period).checks()).isEmpty();
        }

        @Test
        @DisplayName("cleared back to zero by the period end: no warning")
        void clearedDoesNotWarn() {
            when(ledger.balanceAsOf(CLEARING, END)).thenReturn(new BigDecimal("0.004"));
            when(ledger.balanceAsOf(CLEARING, END.minusDays(90))).thenReturn(new BigDecimal("-45.67"));

            assertThat(service.evaluate(period).checks()).isEmpty();
        }

        @Test
        @DisplayName("[M] a REVERSED OTHER adjustment nominates no clearing account")
        void reversedAdjustmentNominatesNothing() {
            other.setStatus(AdjustmentStatus.REVERSED);
            when(ledger.balanceAsOf(CLEARING, END)).thenReturn(new BigDecimal("-45.67"));
            when(ledger.balanceAsOf(CLEARING, END.minusDays(90))).thenReturn(new BigDecimal("-45.67"));

            assertThat(service.evaluate(period).checks()).isEmpty();
            verify(ledgerEntries, never()).accountsOf(any());
        }

        @Test
        @DisplayName("no OTHER adjustment ever posted: not evaluated")
        void notEvaluatedWithoutOtherAdjustments() {
            when(adjustments.findAllOfType(BankAdjustmentType.OTHER)).thenReturn(List.of());

            assertThat(service.evaluate(period).checks()).isEmpty();
            verify(ledgerEntries, never()).accountsOf(any());
        }
    }

    // ---- policy -----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the close decision (§5.2, I5)")
    class Decide {

        @BeforeEach
        void blocked() {
            BankReconciliation inProgress = recon(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15), null);
            inProgress.setStatus(ReconciliationStatus.IN_PROGRESS);
            when(reconciliations
                            .findByGlAccount_GlAccountIdAndStatusInAndStatementEndDateLessThanEqualOrderByStatementStartDateAsc(
                                    eq(CASH), anyCollection(), eq(END)))
                    .thenReturn(List.of(inProgress));
        }

        @Test
        @DisplayName("AC2: REQUIRED_WITH_EXCEPTION without an exception refuses with the account and its codes")
        void refusedWithoutException() {
            CloseReadinessResponse readiness = service.evaluate(period);

            assertThatThrownBy(() -> service.decide(readiness, null))
                    .isInstanceOfSatisfying(PeriodBankReconciliationIncompleteException.class, e -> {
                        assertThat(e.getUnreconciledAccounts()).singleElement().satisfies(a -> {
                            assertThat(a.glAccountId()).isEqualTo(CASH);
                            assertThat(a.checkCodes()).containsExactly("RECONCILIATION_IN_FLIGHT");
                        });
                        assertThat(e.getRefusedExceptionReason()).isNull();
                    });
        }

        @Test
        @DisplayName("AC3: close + override + justification grants the exception")
        void exceptionGranted() {
            authorities("accounting:period:close", "accounting:period:override");

            var decision =
                    service.decide(service.evaluate(period), exception("Bank statement delayed; controller approved"));

            assertThat(decision.exceptionGranted()).isTrue();
            assertThat(decision.bankReconciliationReady()).isFalse();
        }

        @Test
        @DisplayName("[M] AC4: without override the exception is 403 PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED")
        void exceptionWithoutOverride() {
            authorities("accounting:period:close");
            CloseReadinessResponse readiness = service.evaluate(period);

            assertThatThrownBy(
                            () -> service.decide(readiness, exception("Bank statement delayed; controller approved")))
                    .isInstanceOf(PeriodCloseExceptionNotPermittedException.class);
        }

        @Test
        @DisplayName("a justification under 10 characters is 400 JUSTIFICATION_REQUIRED")
        void shortJustification() {
            authorities("accounting:period:close", "accounting:period:override");
            CloseReadinessResponse readiness = service.evaluate(period);

            assertThatThrownBy(() -> service.decide(readiness, exception("too short")))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));
        }

        @Test
        @DisplayName("an exception body without a justification is 400 VALIDATION_ERROR")
        void missingJustification() {
            authorities("accounting:period:close", "accounting:period:override");
            CloseReadinessResponse readiness = service.evaluate(period);

            assertThatThrownBy(() -> service.decide(readiness, exception("   ")))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR));
        }

        @Test
        @DisplayName("AC5: REQUIRED refuses an exception with 422, reporting it as not permitted by policy")
        void requiredRefusesException() {
            policy(BankRecClosePolicy.REQUIRED, 0);
            authorities("accounting:period:close", "accounting:period:override");
            CloseReadinessResponse readiness = service.evaluate(period);

            assertThatThrownBy(
                            () -> service.decide(readiness, exception("Bank statement delayed; controller approved")))
                    .isInstanceOfSatisfying(
                            PeriodBankReconciliationIncompleteException.class,
                            e -> assertThat(e.getRefusedExceptionReason()).contains("REQUIRED"));
        }

        @Test
        @DisplayName("AC6: ADVISORY closes, not ready, and still lists every check")
        void advisoryCloses() {
            policy(BankRecClosePolicy.ADVISORY, 0);
            CloseReadinessResponse readiness = service.evaluate(period);

            assertThat(readiness.ready()).isTrue();
            assertThat(readiness.blockingCount()).isEqualTo(1);
            var decision = service.decide(readiness, exception("ignored under advisory policy"));
            assertThat(decision.bankReconciliationReady()).isFalse();
            assertThat(decision.exceptionGranted()).isFalse();
        }

        @Test
        @DisplayName("ADVISORY: DRAFT entries alone make readiness false")
        void advisoryDraftsNotReady() {
            policy(BankRecClosePolicy.ADVISORY, 0);
            when(ledgerEntries.draftEntryIds(any(), any())).thenReturn(List.of(UUID.randomUUID()));

            CloseReadinessResponse readiness = service.evaluate(period);

            assertThat(readiness.ready()).isFalse();
            assertThat(codes(readiness.checks())).containsExactly(ReadinessCheckCode.DRAFT_JOURNAL_ENTRIES);
        }

        @Test
        @DisplayName("the summary names the policy, counts and each blocked account's codes")
        void summary() {
            assertThat(BankReconciliationCloseReadiness.summary(service.evaluate(period)))
                    .isEqualTo("policy=REQUIRED_WITH_EXCEPTION;ready=false;blocking=1;warning=0;"
                            + "unreconciled=1000:RECONCILIATION_IN_FLIGHT");
        }
    }
}
