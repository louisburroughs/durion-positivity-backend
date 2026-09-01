package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.DatabaseDialectSupport;
import com.positivity.accounting.internal.dto.AgedPayablesReport;
import com.positivity.accounting.internal.dto.AgedReceivablesReport;
import com.positivity.accounting.internal.dto.GeneralLedgerAccountSection;
import com.positivity.accounting.internal.dto.GeneralLedgerLine;
import com.positivity.accounting.internal.dto.GeneralLedgerReport;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.CreditMemoTaxRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for Story G2 (issue #960) report generation:
 * {@link FinancialReportingServiceImpl#generateGeneralLedger},
 * {@link FinancialReportingServiceImpl#generateAgedReceivables}, and
 * {@link FinancialReportingServiceImpl#generateAgedPayables}.
 *
 * <p>Covers GL opening/running/closing balances (including a reversed+reversing
 * pair netting to zero), all-account section ordering, aging bucket boundaries
 * (days 30/31/60/61/90/91, not-yet-due, no-due-date fallback), and empty-data
 * shapes.
 *
 * <p><strong>Aging rule under test (issue #1604).</strong> Both reports age from the item's DUE
 * date, falling back to the item's document date (invoice date for A/R, bill date for A/P) when no
 * due date is recorded. Two separate concerns are pinned separately here:
 *
 * <ul>
 *   <li><em>Existence</em> — an item whose DOCUMENT date is after {@code asOfDate} did not exist
 *       yet and is excluded from the report entirely.
 *   <li><em>Aging</em> — an item that existed is bucketed by days past its DUE date. Not-yet-due
 *       items have a negative age and land in {@code current}.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FinancialReportingG2ServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T12:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);
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
    private GLAccountRepository glAccountRepository;

    @Mock
    private ExtInvoiceRepository extInvoiceRepository;

    @Mock
    private ExtInvoiceTaxRepository extInvoiceTaxRepository;

    @Mock
    private CreditMemoRepository creditMemoRepository;

    @Mock
    private CreditMemoTaxRepository creditMemoTaxRepository;

    @Mock
    private VendorBillRepository vendorBillRepository;

    @Mock
    private APPaymentAllocationRepository apPaymentAllocationRepository;

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

    // ================= General Ledger =================

    @Test
    @DisplayName("GL single account: opening seeds running balance; closing = opening + in-period net")
    void generalLedgerRunningAndClosingBalances() {
        GLAccount cash = glAccount(ACCT_CASH, "1000", "Cash");
        JournalEntry e1 = postedEntry("JE-202606-1", LocalDateTime.of(2026, 6, 5, 0, 0));
        line(e1, cash, new BigDecimal("30000.00"), BigDecimal.ZERO);
        JournalEntry e2 = postedEntry("JE-202606-2", LocalDateTime.of(2026, 6, 20, 0, 0));
        line(e2, cash, BigDecimal.ZERO, new BigDecimal("5000.00"));

        when(journalEntryRepository.findPostedEntriesForAccount(
                        eq(ACCT_CASH), eq(START.atStartOfDay()), eq(END.atTime(LocalTime.MAX))))
                .thenReturn(List.of(e1, e2));
        when(journalEntryRepository.sumPostedBalanceForAccountBefore(eq(ACCT_CASH), eq(START.atStartOfDay())))
                .thenReturn(new BigDecimal("50000.00"));
        when(glAccountRepository.findAllById(any())).thenReturn(List.of(cash));

        GeneralLedgerReport report = service.generateGeneralLedger(ACCT_CASH.toString(), START, END);

        assertThat(report.getAccountId()).isEqualTo(ACCT_CASH.toString());
        assertThat(report.getGeneratedAt()).isEqualTo(FIXED_NOW);
        assertThat(report.getAccounts()).hasSize(1);
        GeneralLedgerAccountSection section = report.getAccounts().get(0);
        assertThat(section.getAccountNumber()).isEqualTo("1000");
        assertThat(section.getAccountName()).isEqualTo("Cash");
        assertThat(section.getOpeningBalance()).isEqualByComparingTo("50000.00");
        assertThat(section.getLines())
                .extracting(GeneralLedgerLine::getRunningBalance)
                .containsExactly(new BigDecimal("80000.00"), new BigDecimal("75000.00"));
        assertThat(section.getTotalDebit()).isEqualByComparingTo("30000.00");
        assertThat(section.getTotalCredit()).isEqualByComparingTo("5000.00");
        assertThat(section.getClosingBalance()).isEqualByComparingTo("75000.00");
        assertThat(report.getTotalDebit()).isEqualByComparingTo("30000.00");
        assertThat(report.getTotalCredit()).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("GL: a POSTED reversing entry cancels its POSTED original — pair nets to zero")
    void generalLedgerReversingPairNetsToZero() {
        GLAccount cash = glAccount(ACCT_CASH, "1000", "Cash");
        JournalEntry original = postedEntry("JE-202606-1", LocalDateTime.of(2026, 6, 10, 0, 0));
        line(original, cash, new BigDecimal("100.00"), BigDecimal.ZERO);
        JournalEntry reversing = postedEntry("JE-202606-2", LocalDateTime.of(2026, 6, 11, 0, 0));
        line(reversing, cash, BigDecimal.ZERO, new BigDecimal("100.00"));

        when(journalEntryRepository.findPostedEntriesForAccount(eq(ACCT_CASH), any(), any()))
                .thenReturn(List.of(original, reversing));
        when(journalEntryRepository.sumPostedBalanceForAccountBefore(eq(ACCT_CASH), any()))
                .thenReturn(BigDecimal.ZERO);
        when(glAccountRepository.findAllById(any())).thenReturn(List.of(cash));

        GeneralLedgerReport report = service.generateGeneralLedger(ACCT_CASH.toString(), START, END);

        GeneralLedgerAccountSection section = report.getAccounts().get(0);
        assertThat(section.getClosingBalance()).isEqualByComparingTo("0.00");
        assertThat(section.getTotalDebit()).isEqualByComparingTo("100.00");
        assertThat(section.getTotalCredit()).isEqualByComparingTo("100.00");
        assertThat(report.getTotalDebit()).isEqualByComparingTo("100.00");
        assertThat(report.getTotalCredit()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("GL all accounts: sections ordered by account number")
    void generalLedgerAllAccountsOrderedByNumber() {
        GLAccount cash = glAccount(ACCT_CASH, "1000", "Cash");
        GLAccount revenue = glAccount(ACCT_REVENUE, "4000", "Revenue");
        JournalEntry entry = postedEntry("JE-202606-1", LocalDateTime.of(2026, 6, 5, 0, 0));
        line(entry, revenue, BigDecimal.ZERO, new BigDecimal("200.00"));
        line(entry, cash, new BigDecimal("200.00"), BigDecimal.ZERO);

        when(journalEntryRepository.findPostedEntriesInRange(eq(START.atStartOfDay()), eq(END.atTime(LocalTime.MAX))))
                .thenReturn(List.of(entry));
        when(journalEntryRepository.sumPostedBalanceForAccountBefore(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(glAccountRepository.findAllById(any())).thenReturn(List.of(cash, revenue));

        GeneralLedgerReport report = service.generateGeneralLedger(null, START, END);

        assertThat(report.getAccountId()).isNull();
        assertThat(report.getAccounts())
                .extracting(GeneralLedgerAccountSection::getAccountNumber)
                .containsExactly("1000", "4000");
    }

    @Test
    @DisplayName("GL: empty sections and zero totals when no POSTED activity")
    void generalLedgerEmpty() {
        when(journalEntryRepository.findPostedEntriesForAccount(eq(ACCT_CASH), any(), any()))
                .thenReturn(List.of());

        GeneralLedgerReport report = service.generateGeneralLedger(ACCT_CASH.toString(), START, END);

        assertThat(report.getAccounts()).isEmpty();
        assertThat(report.getTotalDebit()).isEqualByComparingTo("0");
        assertThat(report.getTotalCredit()).isEqualByComparingTo("0");
    }

    // ================= Aged Receivables — bucket boundaries =================

    @Test
    @DisplayName("Aged AR: due date drives the bucket, not the invoice date (issue #1604)")
    void agedReceivablesAgesByDueDateNotInvoiceDate() {
        // Raised 45 days ago but not due for another 15 days: under the invoice-date basis this
        // landed in days31To60; under the due-date basis it is not yet due, so it is `current`.
        ExtInvoice invoice = arInvoice(new BigDecimal("100.00"), AS_OF.minusDays(45), AS_OF.plusDays(15));
        when(extInvoiceRepository.findByStatusIn(any())).thenReturn(List.of(invoice));
        when(invoiceBalanceCalculator.isArEligible(any())).thenReturn(true);
        when(invoiceBalanceCalculator.balanceDue(invoice)).thenReturn(invoice.getTotal());

        AgedReceivablesReport report = service.generateAgedReceivables(AS_OF);

        assertThat(report.getTotals().getCurrent()).isEqualByComparingTo("100.00");
        assertThat(report.getTotals().getDays31To60()).isEqualByComparingTo("0");
        assertThat(report.getTotals().getTotalOutstanding()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Aged AR: bucket boundaries 30/31/60/61/90/91 measured from the due date; not-yet-due is current")
    void agedReceivablesBucketBoundaries() {
        // Every invoice is dated long before AS_OF (so all exist as of the report date); only the
        // due date varies, which is what the buckets must key on.
        LocalDate invoiceDate = AS_OF.minusDays(120);
        ExtInvoice notYetDue = arInvoice(new BigDecimal("10.00"), invoiceDate, AS_OF.plusDays(5)); // d=-5 -> current
        ExtInvoice d30 = arInvoice(new BigDecimal("30.00"), invoiceDate, AS_OF.minusDays(30)); // current
        ExtInvoice d31 = arInvoice(new BigDecimal("31.00"), invoiceDate, AS_OF.minusDays(31)); // 31-60
        ExtInvoice d60 = arInvoice(new BigDecimal("60.00"), invoiceDate, AS_OF.minusDays(60)); // 31-60
        ExtInvoice d61 = arInvoice(new BigDecimal("61.00"), invoiceDate, AS_OF.minusDays(61)); // 61-90
        ExtInvoice d90 = arInvoice(new BigDecimal("90.00"), invoiceDate, AS_OF.minusDays(90)); // 61-90
        ExtInvoice d91 = arInvoice(new BigDecimal("91.00"), invoiceDate, AS_OF.minusDays(91)); // 90+

        List<ExtInvoice> all = List.of(notYetDue, d30, d31, d60, d61, d90, d91);
        when(extInvoiceRepository.findByStatusIn(any())).thenReturn(all);
        when(invoiceBalanceCalculator.isArEligible(any())).thenReturn(true);
        for (ExtInvoice invoice : all) {
            when(invoiceBalanceCalculator.balanceDue(invoice)).thenReturn(invoice.getTotal());
        }

        AgedReceivablesReport report = service.generateAgedReceivables(AS_OF);

        assertThat(report.getTotals().getCurrent()).isEqualByComparingTo("40.00"); // 10 not-yet-due + 30
        assertThat(report.getTotals().getDays31To60()).isEqualByComparingTo("91.00"); // 31 + 60
        assertThat(report.getTotals().getDays61To90()).isEqualByComparingTo("151.00"); // 61 + 90
        assertThat(report.getTotals().getDays90Plus()).isEqualByComparingTo("91.00");
        assertThat(report.getTotals().getTotalOutstanding()).isEqualByComparingTo("373.00");
    }

    @Test
    @DisplayName("Aged AR: null due date ages by the invoice date")
    void agedReceivablesNullDueDateAgesByInvoiceDate() {
        // Drafts and replica rows predating V22__ext_invoice_due_date.sql carry no due date.
        ExtInvoice invoice = arInvoice(new BigDecimal("400.00"), AS_OF.minusDays(61), null);
        when(extInvoiceRepository.findByStatusIn(any())).thenReturn(List.of(invoice));
        when(invoiceBalanceCalculator.isArEligible(any())).thenReturn(true);
        when(invoiceBalanceCalculator.balanceDue(invoice)).thenReturn(invoice.getTotal());

        AgedReceivablesReport report = service.generateAgedReceivables(AS_OF);

        assertThat(report.getTotals().getDays61To90()).isEqualByComparingTo("400.00");
        assertThat(report.getTotals().getCurrent()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Aged AR: no due date and no invoiceCreatedAt falls back to finalizedAt for aging")
    void agedReceivablesNoInvoiceDateFallsBackToFinalized() {
        ExtInvoice invoice = ExtInvoice.builder()
                .invoiceId(UUID.randomUUID())
                .partyId(UUID.randomUUID().toString())
                .status("POSTED")
                .total(new BigDecimal("500.00"))
                .invoiceCreatedAt(null)
                .dueDate(null)
                .finalizedAt(AS_OF.minusDays(45).atStartOfDay().toInstant(ZoneOffset.UTC))
                .build();
        when(extInvoiceRepository.findByStatusIn(any())).thenReturn(List.of(invoice));
        when(invoiceBalanceCalculator.isArEligible(any())).thenReturn(true);
        when(invoiceBalanceCalculator.balanceDue(invoice)).thenReturn(invoice.getTotal());

        AgedReceivablesReport report = service.generateAgedReceivables(AS_OF);

        assertThat(report.getTotals().getDays31To60()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("Aged AR: fully-paid (non-positive open) invoices are excluded")
    void agedReceivablesExcludesFullyPaid() {
        ExtInvoice paid = arInvoice(new BigDecimal("100.00"), AS_OF.minusDays(10));
        when(extInvoiceRepository.findByStatusIn(any())).thenReturn(List.of(paid));
        when(invoiceBalanceCalculator.isArEligible(any())).thenReturn(true);
        when(invoiceBalanceCalculator.balanceDue(paid)).thenReturn(BigDecimal.ZERO);

        AgedReceivablesReport report = service.generateAgedReceivables(AS_OF);

        assertThat(report.getRows()).isEmpty();
        assertThat(report.getTotals().getTotalOutstanding()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Aged AR: empty rows and zero totals when nothing is open")
    void agedReceivablesEmpty() {
        when(extInvoiceRepository.findByStatusIn(any())).thenReturn(List.of());

        AgedReceivablesReport report = service.generateAgedReceivables(AS_OF);

        assertThat(report.getRows()).isEmpty();
        assertThat(report.getTotals().getCurrent()).isEqualByComparingTo("0");
        assertThat(report.getTotals().getTotalOutstanding()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Aged AR: invoice whose DOCUMENT date is after asOfDate did not exist yet and is excluded")
    void agedReceivablesExcludesFutureDated() {
        // Existence is tested against the invoice date, never the due date.
        ExtInvoice future = arInvoice(new BigDecimal("100.00"), AS_OF.plusDays(10), AS_OF.plusDays(40));
        when(extInvoiceRepository.findByStatusIn(any())).thenReturn(List.of(future));
        when(invoiceBalanceCalculator.isArEligible(any())).thenReturn(true);
        when(invoiceBalanceCalculator.balanceDue(future)).thenReturn(new BigDecimal("100.00"));

        AgedReceivablesReport report = service.generateAgedReceivables(AS_OF);

        assertThat(report.getRows()).isEmpty();
        assertThat(report.getTotals().getCurrent()).isEqualByComparingTo("0");
        assertThat(report.getTotals().getTotalOutstanding()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Aged AR: not-yet-due invoice with a past document date is INCLUDED in current")
    void agedReceivablesNotYetDueIsIncludedInCurrent() {
        // The converse of agedReceivablesExcludesFutureDated: the invoice exists (document date is
        // in the past) but is not yet due, so it must be reported rather than dropped.
        ExtInvoice notYetDue = arInvoice(new BigDecimal("100.00"), AS_OF.minusDays(10), AS_OF.plusDays(20));
        when(extInvoiceRepository.findByStatusIn(any())).thenReturn(List.of(notYetDue));
        when(invoiceBalanceCalculator.isArEligible(any())).thenReturn(true);
        when(invoiceBalanceCalculator.balanceDue(notYetDue)).thenReturn(new BigDecimal("100.00"));

        AgedReceivablesReport report = service.generateAgedReceivables(AS_OF);

        assertThat(report.getRows()).hasSize(1);
        assertThat(report.getRows().get(0).getCurrent()).isEqualByComparingTo("100.00");
        assertThat(report.getTotals().getCurrent()).isEqualByComparingTo("100.00");
        assertThat(report.getTotals().getTotalOutstanding()).isEqualByComparingTo("100.00");
    }

    // ================= Aged Payables — bucket boundaries =================

    @Test
    @DisplayName("Aged AP: open = total - allocations; bucket boundaries measured from the due date")
    void agedPayablesBucketBoundariesAndOpenBalance() {
        UUID vendorId = UUID.randomUUID();
        // Bill dates are all well before AS_OF (so every bill exists as of the report date); only
        // the due date varies, which is what the buckets must key on.
        LocalDate billDate = AS_OF.minusDays(120);
        VendorBill current = apBill(vendorId, "Acme", new BigDecimal("300.00"), billDate, AS_OF.minusDays(30));
        VendorBill mid = apBill(vendorId, "Acme", new BigDecimal("400.00"), billDate, AS_OF.minusDays(61));
        VendorBill partiallyPaid = apBill(vendorId, "Acme", new BigDecimal("500.00"), billDate, AS_OF.minusDays(91));

        when(vendorBillRepository.findByStatusIn(any())).thenReturn(List.of(current, mid, partiallyPaid));
        // Batched allocation totals (finding 9): bills with no allocation are simply absent (treated 0);
        // only the partially-paid bill returns a row (200 allocated → open 300).
        when(apPaymentAllocationRepository.sumAllocatedAmountByVendorBillIdIn(any()))
                .thenReturn(List.of(allocSum(partiallyPaid.getVendorBillId(), new BigDecimal("200.00"))));

        AgedPayablesReport report = service.generateAgedPayables(AS_OF);

        assertThat(report.getRows()).hasSize(1);
        assertThat(report.getRows().get(0).getVendorName()).isEqualTo("Acme");
        assertThat(report.getTotals().getCurrent()).isEqualByComparingTo("300.00");
        assertThat(report.getTotals().getDays61To90()).isEqualByComparingTo("400.00");
        assertThat(report.getTotals().getDays90Plus()).isEqualByComparingTo("300.00"); // 500 - 200
        assertThat(report.getTotals().getTotalOutstanding()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Aged AP: no due date falls back to bill date for aging")
    void agedPayablesNoDueDateFallsBackToBillDate() {
        UUID vendorId = UUID.randomUUID();
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setVendorId(vendorId);
        bill.setVendorName("Globex");
        bill.setTotalAmount(new BigDecimal("250.00"));
        bill.setStatus(VendorBillStatus.APPROVED);
        bill.setDueDate(null);
        bill.setBillDate(AS_OF.minusDays(75).atStartOfDay());

        when(vendorBillRepository.findByStatusIn(any())).thenReturn(List.of(bill));
        // No allocation rows returned → the bill's full total is open (finding 9 batched query).
        when(apPaymentAllocationRepository.sumAllocatedAmountByVendorBillIdIn(any()))
                .thenReturn(List.of());

        AgedPayablesReport report = service.generateAgedPayables(AS_OF);

        assertThat(report.getTotals().getDays61To90()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("Aged AP: empty rows and zero totals when nothing is open")
    void agedPayablesEmpty() {
        when(vendorBillRepository.findByStatusIn(any())).thenReturn(List.of());

        AgedPayablesReport report = service.generateAgedPayables(AS_OF);

        assertThat(report.getRows()).isEmpty();
        assertThat(report.getTotals().getTotalOutstanding()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Aged AP: bill whose DOCUMENT (bill) date is after asOfDate did not exist yet and is excluded")
    void agedPayablesExcludesFutureDated() {
        // Existence is tested against the bill date, never the due date.
        UUID vendorId = UUID.randomUUID();
        VendorBill future = apBill(vendorId, "Acme", new BigDecimal("300.00"), AS_OF.plusDays(10), AS_OF.plusDays(40));
        when(vendorBillRepository.findByStatusIn(any())).thenReturn(List.of(future));

        AgedPayablesReport report = service.generateAgedPayables(AS_OF);

        assertThat(report.getRows()).isEmpty();
        assertThat(report.getTotals().getCurrent()).isEqualByComparingTo("0");
        assertThat(report.getTotals().getTotalOutstanding()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Aged AP: not-yet-due bill with a past bill date is INCLUDED in current (issue #1604)")
    void agedPayablesNotYetDueIsIncludedInCurrent() {
        // Previously dropped by the `daysPastDue < 0` guard, which understated totalOutstanding.
        UUID vendorId = UUID.randomUUID();
        VendorBill notYetDue =
                apBill(vendorId, "Acme", new BigDecimal("300.00"), AS_OF.minusDays(10), AS_OF.plusDays(20));
        when(vendorBillRepository.findByStatusIn(any())).thenReturn(List.of(notYetDue));

        AgedPayablesReport report = service.generateAgedPayables(AS_OF);

        assertThat(report.getRows()).hasSize(1);
        assertThat(report.getRows().get(0).getCurrent()).isEqualByComparingTo("300.00");
        assertThat(report.getTotals().getCurrent()).isEqualByComparingTo("300.00");
        assertThat(report.getTotals().getTotalOutstanding()).isEqualByComparingTo("300.00");
    }

    // ================= A/R and A/P share one documented aging rule (issue #1604) =================

    @Test
    @DisplayName("Aging parity: identically dated A/R and A/P items bucket identically (issue #1604)")
    void agingRuleIsIdenticalForReceivablesAndPayables() {
        // Same three shapes on both sides — not yet due, 45 days past due, and no due date at all
        // (falling back to the document date) — must produce the same bucket totals.
        LocalDate documentDate = AS_OF.minusDays(75);

        ExtInvoice arNotYetDue = arInvoice(new BigDecimal("100.00"), documentDate, AS_OF.plusDays(20));
        ExtInvoice arPastDue = arInvoice(new BigDecimal("200.00"), documentDate, AS_OF.minusDays(45));
        ExtInvoice arNoDueDate = arInvoice(new BigDecimal("300.00"), documentDate, null);
        List<ExtInvoice> invoices = List.of(arNotYetDue, arPastDue, arNoDueDate);
        when(extInvoiceRepository.findByStatusIn(any())).thenReturn(invoices);
        when(invoiceBalanceCalculator.isArEligible(any())).thenReturn(true);
        for (ExtInvoice invoice : invoices) {
            when(invoiceBalanceCalculator.balanceDue(invoice)).thenReturn(invoice.getTotal());
        }

        UUID vendorId = UUID.randomUUID();
        VendorBill apNotYetDue = apBill(vendorId, "Acme", new BigDecimal("100.00"), documentDate, AS_OF.plusDays(20));
        VendorBill apPastDue = apBill(vendorId, "Acme", new BigDecimal("200.00"), documentDate, AS_OF.minusDays(45));
        VendorBill apNoDueDate = apBill(vendorId, "Acme", new BigDecimal("300.00"), documentDate, null);
        when(vendorBillRepository.findByStatusIn(any())).thenReturn(List.of(apNotYetDue, apPastDue, apNoDueDate));

        AgedReceivablesReport ar = service.generateAgedReceivables(AS_OF);
        AgedPayablesReport ap = service.generateAgedPayables(AS_OF);

        // not-yet-due -> current; 45 days past due -> 31-60; no due date -> aged from the
        // document date (75 days) -> 61-90.
        assertThat(ar.getTotals().getCurrent()).isEqualByComparingTo("100.00");
        assertThat(ar.getTotals().getDays31To60()).isEqualByComparingTo("200.00");
        assertThat(ar.getTotals().getDays61To90()).isEqualByComparingTo("300.00");

        assertThat(ap.getTotals().getCurrent())
                .isEqualByComparingTo(ar.getTotals().getCurrent());
        assertThat(ap.getTotals().getDays31To60())
                .isEqualByComparingTo(ar.getTotals().getDays31To60());
        assertThat(ap.getTotals().getDays61To90())
                .isEqualByComparingTo(ar.getTotals().getDays61To90());
        assertThat(ap.getTotals().getDays90Plus())
                .isEqualByComparingTo(ar.getTotals().getDays90Plus());
        assertThat(ap.getTotals().getTotalOutstanding())
                .isEqualByComparingTo(ar.getTotals().getTotalOutstanding());
    }

    // ================= Fixtures =================

    private static GLAccount glAccount(UUID id, String code, String name) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(id);
        account.setAccountCode(code);
        account.setAccountName(name);
        return account;
    }

    private static JournalEntry postedEntry(String entryNumber, LocalDateTime transactionDate) {
        JournalEntry entry = new JournalEntry(UUID.randomUUID());
        entry.setEntryNumber(entryNumber);
        entry.setTransactionDate(transactionDate);
        entry.setSourceEventType("TEST_EVENT");
        return entry;
    }

    private static void line(JournalEntry entry, GLAccount account, BigDecimal debit, BigDecimal credit) {
        JournalEntryLine line = new JournalEntryLine();
        line.setGlAccount(account);
        line.setAccountCode(account.getAccountCode());
        line.setAccountName(account.getAccountName());
        line.setDebitAmount(debit);
        line.setCreditAmount(credit);
        line.setJournalEntry(entry);
        line.setLineNumber(entry.getLines() == null ? 1 : entry.getLines().size() + 1);
        entry.addLine(line);
    }

    /** Invoice with no due date recorded — ages from the invoice (document) date. */
    private static ExtInvoice arInvoice(BigDecimal total, LocalDate invoiceDate) {
        return arInvoice(total, invoiceDate, null);
    }

    /** Invoice carrying both dates: {@code invoiceDate} governs existence, {@code dueDate} aging. */
    private static ExtInvoice arInvoice(BigDecimal total, LocalDate invoiceDate, LocalDate dueDate) {
        return ExtInvoice.builder()
                .invoiceId(UUID.randomUUID())
                .partyId(UUID.randomUUID().toString())
                .status("POSTED")
                .total(total)
                .invoiceCreatedAt(invoiceDate.atStartOfDay().toInstant(ZoneOffset.UTC))
                .dueDate(dueDate)
                .build();
    }

    /** Bill whose bill date and due date coincide. */
    private static VendorBill apBill(UUID vendorId, String vendorName, BigDecimal total, LocalDate dueDate) {
        return apBill(vendorId, vendorName, total, dueDate, dueDate);
    }

    /** Bill carrying both dates: {@code billDate} governs existence, {@code dueDate} aging. */
    private static VendorBill apBill(
            UUID vendorId, String vendorName, BigDecimal total, LocalDate billDate, LocalDate dueDate) {
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setVendorId(vendorId);
        bill.setVendorName(vendorName);
        bill.setTotalAmount(total);
        bill.setStatus(VendorBillStatus.APPROVED);
        bill.setDueDate(dueDate == null ? null : dueDate.atStartOfDay());
        bill.setBillDate(billDate.atStartOfDay());
        return bill;
    }

    private static APPaymentAllocationRepository.VendorBillAllocationSum allocSum(
            UUID vendorBillId, BigDecimal amount) {
        return new APPaymentAllocationRepository.VendorBillAllocationSum() {
            @Override
            public UUID getVendorBillId() {
                return vendorBillId;
            }

            @Override
            public BigDecimal getAllocated() {
                return amount;
            }
        };
    }
}
