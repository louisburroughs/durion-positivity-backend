package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.DatabaseDialectSupport;
import com.positivity.accounting.internal.dto.EntryNumberGapCheck;
import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.dto.TrialBalanceAccountTotal;
import com.positivity.accounting.internal.dto.TrialBalanceReport;
import com.positivity.accounting.internal.dto.TrialBalanceRow;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.ExtInvoice;
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
    private com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository extInvoiceTaxRepository;

    @Mock
    private com.positivity.accounting.internal.repository.CreditMemoRepository creditMemoRepository;

    @Mock
    private com.positivity.accounting.internal.repository.CreditMemoTaxRepository creditMemoTaxRepository;

    @Mock
    private com.positivity.accounting.internal.repository.VendorBillRepository vendorBillRepository;

    @Mock
    private com.positivity.accounting.internal.repository.APPaymentAllocationRepository apPaymentAllocationRepository;

    @Mock
    private InvoiceBalanceCalculator invoiceBalanceCalculator;

    @Mock
    private DatabaseDialectSupport databaseDialectSupport;

    private FinancialReportingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FinancialReportingServiceImpl(
                journalEntryRepository,
                statementLineMappingRepository,
                accountingSequenceRepository,
                glAccountRepository,
                extInvoiceRepository,
                extInvoiceTaxRepository,
                creditMemoRepository,
                creditMemoTaxRepository,
                vendorBillRepository,
                apPaymentAllocationRepository,
                invoiceBalanceCalculator,
                databaseDialectSupport,
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
        when(databaseDialectSupport.isPostgreSql()).thenReturn(true);
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
        when(databaseDialectSupport.isPostgreSql()).thenReturn(true);
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
        when(databaseDialectSupport.isPostgreSql()).thenReturn(true);
        when(accountingSequenceRepository.findAllByOrderByScopeKeyAsc()).thenReturn(List.of());

        TrialBalanceReport report = service.generateTrialBalance(AS_OF);

        assertThat(report.getBalanced()).isTrue();
    }

    @Test
    @DisplayName("Populates gap footnote only for JE scopes up to the as-of month that actually have gaps")
    void populatesGapFootnoteForJeScopesUpToAsOfMonth() {
        aggregatedTotals(new TrialBalanceAccountTotal(
                ACCT_CASH, "1000", "Cash", new BigDecimal("10.00"), new BigDecimal("10.00")));
        when(databaseDialectSupport.isPostgreSql()).thenReturn(true);
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
    @DisplayName("Reports an empty gap footnote without touching the PostgreSQL-only query on another dialect")
    void skipsGapFootnoteOnNonPostgreSqlDialect() {
        // Regression guard for issue #1244: the gap query uses CROSS JOIN LATERAL
        // generate_series and fails to parse on H2, so a populated
        // accounting_sequence table used to break Trial Balance (and the report
        // exports built on it) in every H2-backed test and in the dev profile.
        aggregatedTotals(new TrialBalanceAccountTotal(
                ACCT_CASH, "1000", "Cash", new BigDecimal("10.00"), new BigDecimal("10.00")));
        // Stated, not inherited from the mock default: the dialect is the subject of this test.
        when(databaseDialectSupport.isPostgreSql()).thenReturn(false);

        TrialBalanceReport report = service.generateTrialBalance(AS_OF);

        assertThat(report.getEntryNumberGaps()).isEmpty();
        assertThat(report.getBalanced()).isTrue();
        verifyNoInteractions(accountingSequenceRepository);
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

    @Test
    @DisplayName("#1629: tax-liability report excludes a deposit-take invoice's tax rows, "
            + "keeping only the settlement invoice's")
    void taxLiability_excludesDepositTakeInvoiceTax() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 30);
        Instant finalizedAt = start.atStartOfDay().toInstant(ZoneOffset.UTC);

        UUID depositInvoiceId = UUID.fromString("a2000000-0000-7000-8000-000000000001");
        UUID settlementInvoiceId = UUID.fromString("a2000000-0000-7000-8000-000000000002");

        // Marked row (depositSourceType set): its tax was minted before the #1629 source fix and
        // must never reach the report.
        ExtInvoice depositInvoice = ExtInvoice.builder()
                .invoiceId(depositInvoiceId)
                .status("FINALIZED")
                .finalizedAt(finalizedAt)
                .depositSourceType("WORKORDER")
                .total(new BigDecimal("100.00"))
                .build();
        ExtInvoice settlementInvoice = ExtInvoice.builder()
                .invoiceId(settlementInvoiceId)
                .status("FINALIZED")
                .finalizedAt(finalizedAt)
                .depositSourceType(null)
                .total(new BigDecimal("216.00"))
                .build();
        when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                .thenReturn(List.of(depositInvoice, settlementInvoice));

        com.positivity.accounting.internal.entity.ExtInvoiceTax depositTaxRow =
                com.positivity.accounting.internal.entity.ExtInvoiceTax.builder()
                        .invoiceId(depositInvoiceId)
                        .jurisdictionType("STATE")
                        .jurisdictionCode("WA")
                        .rate(new BigDecimal("0.08"))
                        .taxableBase(new BigDecimal("100.00"))
                        .taxAmount(new BigDecimal("8.00"))
                        .exempt(false)
                        .build();
        com.positivity.accounting.internal.entity.ExtInvoiceTax settlementTaxRow =
                com.positivity.accounting.internal.entity.ExtInvoiceTax.builder()
                        .invoiceId(settlementInvoiceId)
                        .jurisdictionType("STATE")
                        .jurisdictionCode("WA")
                        .rate(new BigDecimal("0.08"))
                        .taxableBase(new BigDecimal("200.00"))
                        .taxAmount(new BigDecimal("16.00"))
                        .exempt(false)
                        .build();
        // Fix #5 test-honesty: stub with an Answer that actually filters by the requested ids,
        // rather than unconditionally returning only the settlement row — otherwise the money
        // assertions below can't fail even if the source-side #1629 filter is deleted.
        java.util.Map<UUID, com.positivity.accounting.internal.entity.ExtInvoiceTax> taxRowsByInvoiceId =
                java.util.Map.of(depositInvoiceId, depositTaxRow, settlementInvoiceId, settlementTaxRow);
        when(extInvoiceTaxRepository.findByInvoiceIdIn(any())).thenAnswer(invocation -> {
            List<UUID> requestedIds = invocation.getArgument(0);
            return requestedIds.stream()
                    .map(taxRowsByInvoiceId::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        });

        TaxLiabilityReport report = service.generateTaxLiability(start, end);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(extInvoiceTaxRepository).findByInvoiceIdIn(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(settlementInvoiceId);

        assertThat(report.getTotalTaxCollectedGross()).isEqualByComparingTo("16.00");
        assertThat(report.getTotalTaxableBase()).isEqualByComparingTo("200.00");
        assertThat(report.getRows()).hasSize(1);
        assertThat(report.getRows().getFirst().getTaxCollectedGross()).isEqualByComparingTo("16.00");
    }

    /**
     * #1629 (Fix 4): a credit memo against a deposit-take original must contribute nothing to
     * the tax-liability report — its original invoice's tax was never counted on the gross side,
     * so netting the credit-side reversal would drive the jurisdiction's netTax negative against
     * tax that was never counted.
     */
    @Test
    @DisplayName("#1629: a credit memo against a deposit-take invoice contributes nothing to the tax-liability report")
    void taxLiability_creditAgainstDepositTakeInvoice_contributesNothing() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 30);
        Instant finalizedAt = start.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant postedAt = finalizedAt.plusSeconds(3600);

        UUID depositInvoiceId = UUID.fromString("a3000000-0000-7000-8000-000000000001");

        ExtInvoice depositInvoice = ExtInvoice.builder()
                .invoiceId(depositInvoiceId)
                .status("FINALIZED")
                .finalizedAt(finalizedAt)
                .depositSourceType("WORKORDER")
                .total(new BigDecimal("100.00"))
                .build();
        // No invoices finalized in-window other than the (excluded) deposit-take one.
        when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of());

        com.positivity.accounting.internal.entity.CreditMemo creditAgainstDeposit =
                new com.positivity.accounting.internal.entity.CreditMemo();
        creditAgainstDeposit.setOriginalInvoiceId(depositInvoiceId);
        creditAgainstDeposit.setCustomerId(UUID.randomUUID());
        creditAgainstDeposit.setCreditAmount(new BigDecimal("100.00"));
        creditAgainstDeposit.setTaxAmountReversed(new BigDecimal("8.00"));
        creditAgainstDeposit.setStatus(com.positivity.accounting.internal.enums.CreditMemoStatus.POSTED);
        creditAgainstDeposit.setPostedTimestamp(postedAt);
        when(creditMemoRepository.findByStatusNotAndPostedTimestampBetween(any(), any(), any()))
                .thenReturn(List.of(creditAgainstDeposit));
        // The deposit flag is loaded fresh by originalInvoiceId (the original can sit outside the
        // report window) — stub findAllById to actually filter by the requested ids.
        when(extInvoiceRepository.findAllById(any())).thenAnswer(invocation -> {
            Iterable<UUID> requestedIds = invocation.getArgument(0);
            List<ExtInvoice> all = List.of(depositInvoice);
            List<ExtInvoice> matched = new java.util.ArrayList<>();
            for (UUID id : requestedIds) {
                all.stream()
                        .filter(inv -> inv.getInvoiceId().equals(id))
                        .findFirst()
                        .ifPresent(matched::add);
            }
            return matched;
        });

        TaxLiabilityReport report = service.generateTaxLiability(start, end);

        // Excluded before ever reaching CreditMemoTax/ExtInvoiceTax attribution lookups.
        verify(creditMemoTaxRepository, never()).findByCreditMemoIdIn(any());
        assertThat(report.getTotalCreditsNetted()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getTotalNetTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getRows()).isEmpty();
    }
}
