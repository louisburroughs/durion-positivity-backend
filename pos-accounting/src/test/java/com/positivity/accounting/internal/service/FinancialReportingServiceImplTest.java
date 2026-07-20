package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.EntryNumberGapCheck;
import com.positivity.accounting.internal.dto.TrialBalanceAccountTotal;
import com.positivity.accounting.internal.dto.TrialBalanceReport;
import com.positivity.accounting.internal.dto.TrialBalanceRow;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link FinancialReportingServiceImpl#generateTrialBalance}
 * (story G1, issue #956): per-account aggregation mapping, balanced-flag
 * computation, entry-number gap footnote scoping, empty-ledger shape, and
 * inclusive as-of date boundary.
 */
@ExtendWith(MockitoExtension.class)
class FinancialReportingServiceImplTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T12:00:00Z");
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

    private static final UUID ACCT_CASH = UUID.fromString("a1000000-0000-7000-8000-000000000001");
    private static final UUID ACCT_REVENUE = UUID.fromString("a1000000-0000-7000-8000-000000000002");

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private StatementLineMappingRepository statementLineMappingRepository;

    @Mock
    private AccountingSequenceRepository accountingSequenceRepository;

    @Mock
    private com.positivity.accounting.internal.repository.GLAccountRepository glAccountRepository;

    @Mock
    private com.positivity.accounting.internal.repository.ExtInvoiceRepository extInvoiceRepository;

    @Mock
    private com.positivity.accounting.internal.repository.VendorBillRepository vendorBillRepository;

    @Mock
    private com.positivity.accounting.internal.repository.APPaymentAllocationRepository apPaymentAllocationRepository;

    @Mock
    private InvoiceBalanceCalculator invoiceBalanceCalculator;

    private FinancialReportingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FinancialReportingServiceImpl(
                journalEntryRepository,
                statementLineMappingRepository,
                accountingSequenceRepository,
                glAccountRepository,
                extInvoiceRepository,
                vendorBillRepository,
                apPaymentAllocationRepository,
                invoiceBalanceCalculator,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    private void aggregatedTotals(TrialBalanceAccountTotal... totals) {
        when(journalEntryRepository.sumPostedDebitsCreditsByAccountAsOf(AS_OF.atTime(LocalTime.MAX)))
                .thenReturn(List.of(totals));
    }

    private static AccountingSequence sequence(String scopeKey) {
        AccountingSequence sequence = new AccountingSequence();
        sequence.setScopeKey(scopeKey);
        sequence.setNextValue(10L);
        return sequence;
    }

    @Test
    @DisplayName("Maps per-account aggregates to rows with signed balance, preserving account-number order")
    void mapsAggregatesToRowsWithSignedBalances() {
        aggregatedTotals(
                new TrialBalanceAccountTotal(
                        ACCT_CASH, "1000", "Cash", new BigDecimal("150.00"), new BigDecimal("40.00")),
                new TrialBalanceAccountTotal(
                        ACCT_REVENUE, "4000", "Revenue", new BigDecimal("40.00"), new BigDecimal("150.00")));
        when(accountingSequenceRepository.findAllByOrderByScopeKeyAsc()).thenReturn(List.of());

        TrialBalanceReport report = service.generateTrialBalance(AS_OF);

        assertThat(report.getAsOfDate()).isEqualTo(AS_OF);
        assertThat(report.getGeneratedAt()).isEqualTo(FIXED_NOW);
        assertThat(report.getRows())
                .extracting(
                        TrialBalanceRow::getAccountId,
                        TrialBalanceRow::getAccountNumber,
                        TrialBalanceRow::getAccountName,
                        TrialBalanceRow::getTotalDebit,
                        TrialBalanceRow::getTotalCredit,
                        TrialBalanceRow::getBalance)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                ACCT_CASH.toString(),
                                "1000",
                                "Cash",
                                new BigDecimal("150.00"),
                                new BigDecimal("40.00"),
                                new BigDecimal("110.00")),
                        org.assertj.core.groups.Tuple.tuple(
                                ACCT_REVENUE.toString(),
                                "4000",
                                "Revenue",
                                new BigDecimal("40.00"),
                                new BigDecimal("150.00"),
                                new BigDecimal("-110.00")));
        assertThat(report.getTotalDebit()).isEqualByComparingTo("190.00");
        assertThat(report.getTotalCredit()).isEqualByComparingTo("190.00");
        assertThat(report.getBalanced()).isTrue();
        assertThat(report.getEntryNumberGaps()).isEmpty();
    }

    @Test
    @DisplayName("Computes balanced=false when sum of debits does not equal sum of credits")
    void computesBalancedFalseForUnbalancedLedger() {
        aggregatedTotals(
                new TrialBalanceAccountTotal(ACCT_CASH, "1000", "Cash", new BigDecimal("100.00"), BigDecimal.ZERO),
                new TrialBalanceAccountTotal(
                        ACCT_REVENUE, "4000", "Revenue", BigDecimal.ZERO, new BigDecimal("90.00")));
        when(accountingSequenceRepository.findAllByOrderByScopeKeyAsc()).thenReturn(List.of());

        TrialBalanceReport report = service.generateTrialBalance(AS_OF);

        assertThat(report.getTotalDebit()).isEqualByComparingTo("100.00");
        assertThat(report.getTotalCredit()).isEqualByComparingTo("90.00");
        assertThat(report.getBalanced()).isFalse();
    }

    @Test
    @DisplayName("Balanced compares numeric value, not BigDecimal scale")
    void balancedIgnoresBigDecimalScaleDifferences() {
        aggregatedTotals(new TrialBalanceAccountTotal(
                ACCT_CASH, "1000", "Cash", new BigDecimal("100.0"), new BigDecimal("100.00")));
        when(accountingSequenceRepository.findAllByOrderByScopeKeyAsc()).thenReturn(List.of());

        TrialBalanceReport report = service.generateTrialBalance(AS_OF);

        assertThat(report.getBalanced()).isTrue();
    }

    @Test
    @DisplayName("Populates gap footnote only for JE scopes up to the as-of month that actually have gaps")
    void populatesGapFootnoteForJeScopesUpToAsOfMonth() {
        aggregatedTotals(new TrialBalanceAccountTotal(
                ACCT_CASH, "1000", "Cash", new BigDecimal("10.00"), new BigDecimal("10.00")));
        when(accountingSequenceRepository.findAllByOrderByScopeKeyAsc())
                .thenReturn(List.of(
                        sequence("INV-202605"), sequence("JE-202605"), sequence("JE-202606"), sequence("JE-202607")));
        when(accountingSequenceRepository.findMissingEntryNumbers("JE-202605")).thenReturn(List.of(3L, 7L));
        when(accountingSequenceRepository.findMissingEntryNumbers("JE-202606")).thenReturn(List.of());

        TrialBalanceReport report = service.generateTrialBalance(AS_OF);

        assertThat(report.getEntryNumberGaps())
                .extracting(EntryNumberGapCheck::getScopeKey, EntryNumberGapCheck::getMissingNumbers)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("JE-202605", List.of(3L, 7L)));
        // Scopes after the as-of month and non-JE scopes are never gap-checked
        verify(accountingSequenceRepository, never()).findMissingEntryNumbers("JE-202607");
        verify(accountingSequenceRepository, never()).findMissingEntryNumbers("INV-202605");
    }

    @Test
    @DisplayName("Returns empty rows, zero totals, balanced=true, and empty gap footnote when no POSTED data exists")
    void returnsEmptyBalancedReportForEmptyLedger() {
        aggregatedTotals();

        TrialBalanceReport report = service.generateTrialBalance(AS_OF);

        assertThat(report.getRows()).isEmpty();
        assertThat(report.getTotalDebit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getTotalCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getBalanced()).isTrue();
        assertThat(report.getEntryNumberGaps()).isEmpty();
        verifyNoInteractions(accountingSequenceRepository);
    }

    @Test
    @DisplayName("Queries the aggregate with an inclusive end-of-day as-of boundary")
    void passesInclusiveEndOfDayBoundaryToRepository() {
        aggregatedTotals();

        service.generateTrialBalance(AS_OF);

        ArgumentCaptor<LocalDateTime> asOfCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(journalEntryRepository).sumPostedDebitsCreditsByAccountAsOf(asOfCaptor.capture());
        assertThat(asOfCaptor.getValue()).isEqualTo(AS_OF.atTime(LocalTime.MAX));
    }
}
