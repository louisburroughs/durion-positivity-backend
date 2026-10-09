package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.DatabaseDialectSupport;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.AccountDrilldownResponse;
import com.positivity.accounting.internal.dto.BalanceSheetReport;
import com.positivity.accounting.internal.dto.IncomeStatementReport;
import com.positivity.accounting.internal.dto.TrialBalanceAccountTotal;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.enums.AccountSubtype;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Ledger balances are stubbed as the grouped repository query returns them, debits minus
 * credits, so a credit-normal account with activity is a negative number going in. Accounts with a
 * balance and no mapping land on the computed lines (CAP:550 S35, #2524).
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

    @Mock
    private DisplayReferenceResolver displayReferenceResolver;

    private FinancialReportingServiceImpl service;

    private final List<GLAccount> accounts = new ArrayList<>();
    private final List<StatementLineMapping> mappings = new ArrayList<>();
    /** Ledger balance per account, debits minus credits, as the grouped query reports it. */
    private final Map<GLAccount, BigDecimal> balances = new LinkedHashMap<>();

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
                displayReferenceResolver,
                Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC),
                new LedgerCurrency("USD"),
                org.mockito.Mockito.mock(TypedOutputTax.class));
        // The chart lookup answers for whichever ids the service asks about.
        lenient().when(glAccountRepository.findAllById(any())).thenAnswer(invocation -> {
            Collection<UUID> ids = new ArrayList<>();
            for (UUID id : invocation.<Iterable<UUID>>getArgument(0)) {
                ids.add(id);
            }
            return accounts.stream()
                    .filter(account -> ids.contains(account.getGlAccountId()))
                    .toList();
        });
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
        BalanceSheetReport report = balanceSheet();

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
    @DisplayName("Balance sheet: an account with a balance and no mapping lands on the computed line for its type,"
            + " a BANK_CASH asset on BS_IN_THE_BANK, revenue less expenses on BS_PROFIT_NOT_YET_CLOSED (#2524 AC1,"
            + " AC3)")
    void balanceSheetCollectsUnmappedAccountsOnComputedLines() {
        // Named: 1000 on BS_IN_THE_BANK, 2000 on BS_BILLS_FROM_VENDORS.
        asOfAccount(AccountType.ASSET, "1500.00", "BS_IN_THE_BANK", OperationType.SUM);
        asOfAccount(AccountType.LIABILITY, "-400.00", "BS_BILLS_FROM_VENDORS", OperationType.SUM);
        // Unmapped, with balances: a second bank account, a suspense liability, 2350-style, capital, and
        // the period's revenue and expense.
        GLAccount secondBank = unmapped(AccountType.ASSET, AccountSubtype.BANK_CASH, "250.00");
        GLAccount receivable = unmapped(AccountType.ASSET, AccountSubtype.RECEIVABLE, "300.00");
        GLAccount suspense = unmapped(AccountType.LIABILITY, AccountSubtype.CURRENT_LIABILITY, "-50.00");
        GLAccount capital = unmapped(AccountType.EQUITY, null, "-500.00");
        GLAccount sales = unmapped(AccountType.REVENUE, AccountSubtype.SALES, "-1400.00");
        GLAccount rent = unmapped(AccountType.EXPENSE, AccountSubtype.OPERATING_EXPENSE, "300.00");
        // Unmapped with a zero net balance: never lands anywhere.
        GLAccount settled = unmapped(AccountType.LIABILITY, null, "0.00");

        BalanceSheetReport report = balanceSheet();

        assertThat(report.getLineItems())
                .containsOnlyKeys(
                        "BS_IN_THE_BANK",
                        "BS_BILLS_FROM_VENDORS",
                        "BS_OTHER_ASSETS",
                        "BS_OTHER_LIABILITIES",
                        "BS_OTHER_EQUITY",
                        "BS_PROFIT_NOT_YET_CLOSED");
        assertThat(report.getLineItems().get("BS_IN_THE_BANK")).isEqualByComparingTo("1750.00");
        assertThat(report.getLineItems().get("BS_OTHER_ASSETS")).isEqualByComparingTo("300.00");
        assertThat(report.getLineItems().get("BS_OTHER_LIABILITIES")).isEqualByComparingTo("50.00");
        assertThat(report.getLineItems().get("BS_OTHER_EQUITY")).isEqualByComparingTo("500.00");
        assertThat(report.getLineItems().get("BS_PROFIT_NOT_YET_CLOSED")).isEqualByComparingTo("1100.00");
        assertThat(report.getTotalAssets()).isEqualByComparingTo("2050.00");
        assertThat(report.getTotalLiabilities()).isEqualByComparingTo("450.00");
        assertThat(report.getTotalEquity()).isEqualByComparingTo("1600.00");
        assertThat(report.getBalanced()).isTrue();
        assertThat(List.of(secondBank, receivable, suspense, capital, sales, rent, settled))
                .hasSize(7);
    }

    @Test
    @DisplayName("Balance sheet: computed lines appear only when non-empty; a mapped account without activity keeps"
            + " its named line at zero")
    void balanceSheetOmitsEmptyComputedLines() {
        GLAccount cash = account(AccountType.ASSET);
        map(cash, StatementType.BALANCE_SHEET, "BS_IN_THE_BANK", OperationType.SUM);

        BalanceSheetReport report = balanceSheet();

        assertThat(report.getLineItems()).containsOnlyKeys("BS_IN_THE_BANK");
        assertThat(report.getLineItems().get("BS_IN_THE_BANK")).isEqualByComparingTo("0");
        assertThat(report.getBalanced()).isTrue();
    }

    @Test
    @DisplayName("Income statement: unmapped revenue lands on IS_OTHER_INCOME, unmapped expenses on IS_OTHER_EXPENSES,"
            + " and net income covers them (#2524 AC2)")
    void incomeStatementCollectsUnmappedAccountsOnComputedLines() {
        periodAccount(AccountType.REVENUE, "-1000.00", "IS_SALES", OperationType.SUM);
        periodAccount(AccountType.EXPENSE, "300.00", "IS_COST_OF_PARTS_SOLD", OperationType.SUM);
        unmapped(AccountType.REVENUE, AccountSubtype.OTHER, "-40.00"); // 4930 Cash Over
        unmapped(AccountType.REVENUE, null, "-60.00"); // 4940 Rubber Dust Sales: income, never an expense
        unmapped(AccountType.EXPENSE, AccountSubtype.OPERATING_EXPENSE, "25.00"); // 6040 Cash Short
        unmapped(AccountType.ASSET, AccountSubtype.BANK_CASH, "900.00"); // not this statement's kind

        IncomeStatementReport report = incomeStatement();

        assertThat(report.getLineItems())
                .containsOnlyKeys("IS_SALES", "IS_COST_OF_PARTS_SOLD", "IS_OTHER_INCOME", "IS_OTHER_EXPENSES");
        assertThat(report.getLineItems().get("IS_OTHER_INCOME")).isEqualByComparingTo("100.00");
        assertThat(report.getLineItems().get("IS_OTHER_EXPENSES")).isEqualByComparingTo("25.00");
        assertThat(report.getTotalRevenue()).isEqualByComparingTo("1100.00");
        assertThat(report.getTotalExpenses()).isEqualByComparingTo("325.00");
        assertThat(report.getNetIncome()).isEqualByComparingTo("775.00");
    }

    @Test
    @DisplayName("Drill-down of a computed line lists the accounts it collected with code and type; a named"
            + " BS_IN_THE_BANK drill-down adds the unmapped BANK_CASH accounts (#2524 AC3)")
    void drilldownOfComputedLinesListsTheirAccounts() {
        GLAccount cash = account(AccountType.ASSET);
        cash.setAccountCode("1000");
        cash.setAccountName("Cash");
        balances.put(cash, new BigDecimal("1500.00"));
        map(cash, StatementType.BALANCE_SHEET, "BS_IN_THE_BANK", OperationType.SUM);
        GLAccount secondBank = unmapped(AccountType.ASSET, AccountSubtype.BANK_CASH, "250.00");
        secondBank.setAccountCode("1010");
        secondBank.setAccountName("Savings");
        GLAccount receivable = unmapped(AccountType.ASSET, AccountSubtype.RECEIVABLE, "300.00");
        receivable.setAccountCode("1250");
        receivable.setAccountName("GST Recoverable");
        GLAccount suspense = unmapped(AccountType.LIABILITY, AccountSubtype.CURRENT_LIABILITY, "-50.00");
        suspense.setAccountCode("2350");
        suspense.setAccountName("Settlement Suspense");
        stubAsOfBalances();
        when(statementLineMappingRepository.findByStatementLineCode("BS_IN_THE_BANK"))
                .thenReturn(mappings);
        when(statementLineMappingRepository.findByStatementLineCode("BS_OTHER_ASSETS"))
                .thenReturn(List.of());
        when(statementLineMappingRepository.findByStatementLineCode("BS_OTHER_LIABILITIES"))
                .thenReturn(List.of());
        when(statementLineMappingRepository.findByStatementTypeOrderByDisplayOrderAscStatementLineCodeAsc(
                        StatementType.BALANCE_SHEET))
                .thenReturn(mappings);

        List<AccountDrilldownResponse> bank = service.drilldownToAccounts("BS_IN_THE_BANK", START, END);
        List<AccountDrilldownResponse> otherAssets = service.drilldownToAccounts("BS_OTHER_ASSETS", START, END);
        List<AccountDrilldownResponse> otherLiabilities =
                service.drilldownToAccounts("BS_OTHER_LIABILITIES", START, END);

        assertThat(bank).extracting(AccountDrilldownResponse::getAccountCode).containsExactly("1000", "1010");
        assertThat(bank).extracting(AccountDrilldownResponse::getAccountName).containsExactly("Cash", "Savings");
        assertThat(bank).extracting(AccountDrilldownResponse::getAccountType).containsOnly(AccountType.ASSET);
        assertThat(bank.stream().map(AccountDrilldownResponse::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1750.00");
        assertThat(otherAssets).singleElement().satisfies(row -> {
            assertThat(row.getAccountCode()).isEqualTo("1250");
            assertThat(row.getAccountType()).isEqualTo(AccountType.ASSET);
            assertThat(row.getBalance()).isEqualByComparingTo("300.00");
        });
        assertThat(otherLiabilities).singleElement().satisfies(row -> {
            assertThat(row.getAccountCode()).isEqualTo("2350");
            assertThat(row.getAccountType()).isEqualTo(AccountType.LIABILITY);
            assertThat(row.getBalance()).isEqualByComparingTo("50.00");
        });
        // A balance-sheet drill-down reads as-of balances, never period movement.
        verify(journalEntryRepository, never()).sumPostedDebitsCreditsByAccountInRange(any(), any());
    }

    @Test
    @DisplayName("Drill-down rows carry the amount the account contributes to its line")
    void drilldownMatchesTheLine() {
        GLAccount revenue = account(AccountType.REVENUE);
        revenue.setAccountCode("4000");
        revenue.setAccountName("Service Revenue");
        stubPeriodBalance(revenue, "-1000.00");
        map(revenue, StatementType.INCOME_STATEMENT, "IS_SALES", OperationType.SUM);
        stubPeriodBalances();
        when(statementLineMappingRepository.findByStatementLineCode("IS_SALES")).thenReturn(mappings);

        List<AccountDrilldownResponse> rows = service.drilldownToAccounts("IS_SALES", START, END);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getBalance()).isEqualByComparingTo("1000.00");
            assertThat(row.getAccountCode()).isEqualTo("4000");
            assertThat(row.getAccountName()).isEqualTo("Service Revenue");
            assertThat(row.getAccountType()).isEqualTo(AccountType.REVENUE);
        });
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
        stubPeriodBalances();
        return service.generateIncomeStatement(START, END);
    }

    private BalanceSheetReport balanceSheet() {
        when(statementLineMappingRepository.findByStatementTypeOrderByDisplayOrderAscStatementLineCodeAsc(
                        StatementType.BALANCE_SHEET))
                .thenReturn(mappings);
        stubAsOfBalances();
        return service.generateBalanceSheet(END);
    }

    /** The one grouped period query: every account with a stubbed balance, as the database returns it. */
    private void stubPeriodBalances() {
        when(journalEntryRepository.sumPostedDebitsCreditsByAccountInRange(any(), any()))
                .thenReturn(totals());
    }

    /** The one grouped as-of query. */
    private void stubAsOfBalances() {
        when(journalEntryRepository.sumPostedDebitsCreditsByAccountAsOf(any())).thenReturn(totals());
    }

    private List<TrialBalanceAccountTotal> totals() {
        return balances.entrySet().stream()
                .map(entry -> {
                    BigDecimal balance = entry.getValue();
                    BigDecimal debit = balance.signum() >= 0 ? balance : BigDecimal.ZERO;
                    BigDecimal credit = balance.signum() < 0 ? balance.negate() : BigDecimal.ZERO;
                    return new TrialBalanceAccountTotal(
                            entry.getKey().getGlAccountId(),
                            entry.getKey().getAccountCode(),
                            entry.getKey().getAccountName(),
                            debit,
                            credit);
                })
                .toList();
    }

    /** An account with a balance and no mapping on any statement. */
    private GLAccount unmapped(AccountType type, AccountSubtype subtype, String debitsMinusCredits) {
        GLAccount account = account(type);
        account.setAccountSubtype(subtype);
        account.setAccountCode(String.valueOf(1000 + accounts.size()));
        account.setAccountName(type + " " + accounts.size());
        balances.put(account, new BigDecimal(debitsMinusCredits));
        return account;
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
        balances.put(account, new BigDecimal(debitsMinusCredits));
        map(account, StatementType.BALANCE_SHEET, lineCode, operation);
    }

    private GLAccount account(AccountType type) {
        GLAccount account = new GLAccount(UUID.randomUUID());
        account.setAccountType(type);
        accounts.add(account);
        return account;
    }

    private void stubPeriodBalance(GLAccount account, String debitsMinusCredits) {
        balances.put(account, new BigDecimal(debitsMinusCredits));
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
