package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.DatabaseDialectSupport;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.AccountDrilldownResponse;
import com.positivity.accounting.internal.dto.BalanceSheetReport;
import com.positivity.accounting.internal.dto.IncomeStatementReport;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for how the income statement and balance sheet sign and classify mapped accounts
 * (issue #2394): a line shows each account on its normal side, and the statement totals follow the
 * account's type rather than the statement line code.
 *
 * <p>Ledger balances are stubbed as the repository returns them, debits minus credits, so a
 * credit-normal account with activity is a negative number going in.
 */
@ExtendWith(MockitoExtension.class)
class FinancialReportingStatementClassificationTest {

    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 31);

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

    private final List<GLAccount> accounts = new ArrayList<>();
    private final List<StatementLineMapping> mappings = new ArrayList<>();

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
                Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC),
                new LedgerCurrency("USD"));
    }

    @Test
    @DisplayName("#2394: a revenue account on a line coded plain REVENUE, mapped SUM, is positive revenue")
    void seededShapeIsPositiveRevenue() {
        // The shipped seed's shape: line code REVENUE (no REVENUE_ prefix), operation SUM.
        periodAccount(AccountType.REVENUE, "-1000.00", "REVENUE", OperationType.SUM);

        IncomeStatementReport report = incomeStatement();

        assertThat(report.getLineItems().get("REVENUE")).isEqualByComparingTo("1000.00");
        assertThat(report.getTotalRevenue()).isEqualByComparingTo("1000.00");
        assertThat(report.getTotalExpenses()).isEqualByComparingTo("0");
        assertThat(report.getNetIncome()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("A revenue account already mapped NEGATE is not negated a second time")
    void negateOnRevenueIsNotDoubleNegated() {
        periodAccount(AccountType.REVENUE, "-1000.00", "PL_REVENUE_SALES", OperationType.NEGATE);

        IncomeStatementReport report = incomeStatement();

        assertThat(report.getLineItems().get("PL_REVENUE_SALES")).isEqualByComparingTo("1000.00");
        assertThat(report.getTotalRevenue()).isEqualByComparingTo("1000.00");
        assertThat(report.getNetIncome()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Totals follow the account type whatever the line codes say")
    void totalsFollowAccountTypeNotLineCode() {
        // Codes chosen to defeat the old prefix rules: none starts with REVENUE_ / EXPENSE_, and
        // the expense line contains INCOME, which the old rule read as revenue.
        periodAccount(AccountType.REVENUE, "-1000.00", "SALES", OperationType.SUM);
        periodAccount(AccountType.EXPENSE, "300.00", "INCOME_TAX", OperationType.SUM);

        IncomeStatementReport report = incomeStatement();

        assertThat(report.getLineItems().get("SALES")).isEqualByComparingTo("1000.00");
        assertThat(report.getLineItems().get("INCOME_TAX")).isEqualByComparingTo("300.00");
        assertThat(report.getTotalRevenue()).isEqualByComparingTo("1000.00");
        assertThat(report.getTotalExpenses()).isEqualByComparingTo("300.00");
        assertThat(report.getNetIncome()).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("A line mixing revenue and expense accounts splits between the totals per account")
    void mixedLineSplitsPerAccount() {
        // One "gross profit" line: sales less cost of sales. The operation shapes the line only.
        periodAccount(AccountType.REVENUE, "-1000.00", "GROSS_PROFIT", OperationType.SUM);
        periodAccount(AccountType.EXPENSE, "400.00", "GROSS_PROFIT", OperationType.SUBTRACT);

        IncomeStatementReport report = incomeStatement();

        assertThat(report.getLineItems().get("GROSS_PROFIT")).isEqualByComparingTo("600.00");
        assertThat(report.getTotalRevenue()).isEqualByComparingTo("1000.00");
        assertThat(report.getTotalExpenses()).isEqualByComparingTo("400.00");
        assertThat(report.getNetIncome()).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("An account mapped to two lines counts once in the totals")
    void accountOnTwoLinesCountsOnce() {
        GLAccount revenue = account(AccountType.REVENUE);
        stubPeriodBalance(revenue, "-1000.00");
        map(revenue, StatementType.INCOME_STATEMENT, "SALES", OperationType.SUM);
        map(revenue, StatementType.INCOME_STATEMENT, "SALES_MEMO", OperationType.SUM);

        IncomeStatementReport report = incomeStatement();

        assertThat(report.getLineItems().get("SALES")).isEqualByComparingTo("1000.00");
        assertThat(report.getLineItems().get("SALES_MEMO")).isEqualByComparingTo("1000.00");
        assertThat(report.getTotalRevenue()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("A balance-sheet account on the income statement shows on its line but joins no total")
    void balanceSheetAccountOnIncomeStatementJoinsNoTotal() {
        periodAccount(AccountType.REVENUE, "-1000.00", "REVENUE", OperationType.SUM);
        periodAccount(AccountType.LIABILITY, "-80.00", "REVENUE_TAX_COLLECTED", OperationType.SUM);

        IncomeStatementReport report = incomeStatement();

        assertThat(report.getLineItems().get("REVENUE_TAX_COLLECTED")).isEqualByComparingTo("80.00");
        assertThat(report.getTotalRevenue()).isEqualByComparingTo("1000.00");
        assertThat(report.getNetIncome()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Balance sheet: totals by account type, credit-normal lines positive, equation holds")
    void balanceSheetClassifiesByAccountType() {
        // Dr Cash 1,500 / Cr Payables 400 / Cr Capital 500 / Cr Sales 1,000 / Dr Rent 400.
        // Line codes carry none of the old ASSET_/LIABILITY_/EQUITY_ prefixes.
        asOfAccount(AccountType.ASSET, "1500.00", "CASH", OperationType.SUM);
        asOfAccount(AccountType.LIABILITY, "-400.00", "PAYABLES", OperationType.SUM);
        asOfAccount(AccountType.EQUITY, "-500.00", "CAPITAL", OperationType.NEGATE);
        asOfAccount(AccountType.REVENUE, "-1000.00", "CURRENT_EARNINGS", OperationType.SUM);
        asOfAccount(AccountType.EXPENSE, "400.00", "CURRENT_EARNINGS", OperationType.SUBTRACT);
        when(statementLineMappingRepository.findByStatementTypeOrderByDisplayOrderAscStatementLineCodeAsc(
                        StatementType.BALANCE_SHEET))
                .thenReturn(mappings);
        when(glAccountRepository.findAllById(any())).thenReturn(accounts);

        BalanceSheetReport report = service.generateBalanceSheet(END);

        assertThat(report.getLineItems().get("CASH")).isEqualByComparingTo("1500.00");
        assertThat(report.getLineItems().get("PAYABLES")).isEqualByComparingTo("400.00");
        assertThat(report.getLineItems().get("CAPITAL")).isEqualByComparingTo("500.00");
        assertThat(report.getLineItems().get("CURRENT_EARNINGS")).isEqualByComparingTo("600.00");
        assertThat(report.getTotalAssets()).isEqualByComparingTo("1500.00");
        assertThat(report.getTotalLiabilities()).isEqualByComparingTo("400.00");
        assertThat(report.getTotalEquity()).isEqualByComparingTo("1100.00");
        assertThat(report.getBalanced()).isTrue();
    }

    @Test
    @DisplayName("Drill-down rows carry the amount the account contributes to its line")
    void drilldownMatchesTheLine() {
        GLAccount revenue = account(AccountType.REVENUE);
        stubPeriodBalance(revenue, "-1000.00");
        map(revenue, StatementType.INCOME_STATEMENT, "REVENUE", OperationType.SUM);
        when(statementLineMappingRepository.findByStatementLineCode("REVENUE")).thenReturn(mappings);
        when(glAccountRepository.findAllById(any())).thenReturn(accounts);

        List<AccountDrilldownResponse> rows = service.drilldownToAccounts("REVENUE", START, END);

        assertThat(rows)
                .singleElement()
                .satisfies(row -> assertThat(row.getBalance()).isEqualByComparingTo("1000.00"));
    }

    /**
     * {@code balance} is the ledger balance as stored (debits minus credits); {@code expected} is
     * what the mapping adds to its line.
     */
    @ParameterizedTest(name = "{0} {1} of {2} contributes {3}")
    @CsvSource({
        // Debit-normal types: SUM keeps the stored balance, SUBTRACT and NEGATE reverse it.
        "ASSET,     SUM,      100.00,  100.00",
        "ASSET,     SUBTRACT, 100.00, -100.00",
        "ASSET,     NEGATE,   100.00, -100.00",
        "EXPENSE,   SUM,      100.00,  100.00",
        "EXPENSE,   SUBTRACT, 100.00, -100.00",
        "EXPENSE,   NEGATE,   100.00, -100.00",
        // Credit-normal types: SUM presents the credit balance as positive; NEGATE, which used
        // to be the way to do that, gives the same amount instead of flipping it back.
        "LIABILITY, SUM,      -100.00,  100.00",
        "LIABILITY, SUBTRACT, -100.00, -100.00",
        "LIABILITY, NEGATE,   -100.00,  100.00",
        "EQUITY,    SUM,      -100.00,  100.00",
        "EQUITY,    NEGATE,   -100.00,  100.00",
        "REVENUE,   SUM,      -100.00,  100.00",
        "REVENUE,   SUBTRACT, -100.00, -100.00",
        "REVENUE,   NEGATE,   -100.00,  100.00",
        // A contra balance (a revenue account in debit, e.g. returns) reduces its line under SUM.
        "REVENUE,   SUM,       25.00,  -25.00",
    })
    void contributionByTypeAndOperation(
            AccountType type, OperationType operation, BigDecimal balance, BigDecimal expected) {
        assertThat(FinancialReportingServiceImpl.statementContribution(balance, type, operation))
                .isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("An account of unknown type contributes its stored balance; a missing balance is zero")
    void contributionFallbacks() {
        assertThat(FinancialReportingServiceImpl.statementContribution(
                        new BigDecimal("-100.00"), null, OperationType.SUM))
                .isEqualByComparingTo("-100.00");
        assertThat(FinancialReportingServiceImpl.statementContribution(null, AccountType.REVENUE, OperationType.SUM))
                .isEqualByComparingTo("0");
    }

    // ===== fixtures =====

    private IncomeStatementReport incomeStatement() {
        when(statementLineMappingRepository.findByStatementTypeOrderByDisplayOrderAscStatementLineCodeAsc(
                        StatementType.INCOME_STATEMENT))
                .thenReturn(mappings);
        when(glAccountRepository.findAllById(any())).thenReturn(accounts);
        return service.generateIncomeStatement(START, END);
    }

    /** An account with period activity, mapped onto one income statement line. */
    private void periodAccount(AccountType type, String debitsMinusCredits, String lineCode, OperationType operation) {
        GLAccount account = account(type);
        stubPeriodBalance(account, debitsMinusCredits);
        map(account, StatementType.INCOME_STATEMENT, lineCode, operation);
    }

    /** An account with an as-of balance, mapped onto one balance sheet line. */
    private void asOfAccount(AccountType type, String debitsMinusCredits, String lineCode, OperationType operation) {
        GLAccount account = account(type);
        when(journalEntryRepository.sumPostedBalanceAsOf(eq(account.getGlAccountId()), any()))
                .thenReturn(new BigDecimal(debitsMinusCredits));
        map(account, StatementType.BALANCE_SHEET, lineCode, operation);
    }

    private GLAccount account(AccountType type) {
        GLAccount account = new GLAccount(UUID.randomUUID());
        account.setAccountType(type);
        accounts.add(account);
        return account;
    }

    private void stubPeriodBalance(GLAccount account, String debitsMinusCredits) {
        when(journalEntryRepository.sumPostedBalanceForAccount(eq(account.getGlAccountId()), any(), any()))
                .thenReturn(new BigDecimal(debitsMinusCredits));
    }

    private void map(GLAccount account, StatementType statementType, String lineCode, OperationType operation) {
        mappings.add(StatementLineMapping.builder()
                .mappingId(UUID.randomUUID())
                .glAccount(account)
                .accountName(lineCode)
                .statementType(statementType)
                .statementLineCode(lineCode)
                .displayOrder(mappings.size() + 1)
                .operation(operation)
                .build());
    }
}
