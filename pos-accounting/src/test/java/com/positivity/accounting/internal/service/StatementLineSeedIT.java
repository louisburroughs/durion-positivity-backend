package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.PostgresCommittingTestBase;
import com.positivity.accounting.internal.dto.AccountDrilldownResponse;
import com.positivity.accounting.internal.dto.BalanceSheetReport;
import com.positivity.accounting.internal.dto.GLAccountCreateRequest;
import com.positivity.accounting.internal.dto.IncomeStatementReport;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The seeded statement lines on the full Flyway chain (CAP:550 S35, #2524, acceptance criteria 1-4):
 * the template's named lines reach the registry tenant through the startup sweep, an account with a
 * posted balance and no named line lands on a computed line, drill-down rows add up to their line,
 * and a second run of the repeatable seed leaves each account with at most one line per statement.
 *
 * <p>Requires Docker.
 */
@DisplayName("Seeded statement lines and computed lines (#2524, real Postgres)")
class StatementLineSeedIT extends PostgresCommittingTestBase {

    private static final String DATABASE = "statement-line-seed";
    private static final String SEED = "db/migration/R__seed_reference_accounting.sql";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    private static final java.util.concurrent.atomic.AtomicInteger ENTRY_NUMBERS =
            new java.util.concurrent.atomic.AtomicInteger();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, DATABASE);
        registerCommonProperties(registry);
    }

    @Autowired
    private FinancialReportingService financialReportingService;

    @Autowired
    private GLAccountService glAccountService;

    @Autowired
    private AccountingTenantProvisioner provisioner;

    @Autowired
    private AccountingTemplateReader templateReader;

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private StatementLineMappingRepository statementLineMappingRepository;

    @AfterEach
    void cleanUp() {
        journalEntryRepository.deleteAll();
    }

    @Test
    @DisplayName("AC1/AC2: the named lines carry their accounts, 2350 and 4930/6040 fall on computed lines, profit not"
            + " yet closed balances the sheet, and net income is revenue less expenses")
    void seededLinesAndComputedLines() {
        // One balanced day of business: sales on account and over the counter, inventory bought on
        // credit, a card fee, a suspense credit, a customer credit, and the drawer short.
        post(
                line("1200", "2000.00", "0"), // AR
                line("4000", "0", "1820.00"), // sales
                line("2200", "0", "180.00")); // sales tax collected
        post(line("1090", "600.00", "0"), line("1095", "400.00", "0"), line("1200", "0", "1000.00"));
        post(line("1000", "500.00", "0"), line("1090", "0", "500.00"));
        post(line("1300", "900.00", "0"), line("2000", "0", "900.00"));
        post(line("5000", "300.00", "0"), line("1300", "0", "300.00"));
        post(line("5100", "20.00", "0"), line("1300", "0", "20.00")); // shrinkage
        post(line("6000", "25.00", "0"), line("1000", "0", "25.00"));
        post(line("1000", "40.00", "0"), line("2350", "0", "40.00"));
        post(line("1090", "12.50", "0"), line("2300", "0", "12.50"));
        post(line("1000", "7.00", "0"), line("4930", "0", "7.00"));
        post(line("6040", "3.00", "0"), line("1000", "0", "3.00"));

        BalanceSheetReport balanceSheet = financialReportingService.generateBalanceSheet(DAY);
        Map<String, BigDecimal> bs = balanceSheet.getLineItems();

        assertThat(bs)
                .containsKeys(
                        "BS_IN_THE_BANK",
                        "BS_WAITING_TO_BE_DEPOSITED",
                        "BS_CUSTOMERS_OWE_YOU",
                        "BS_INVENTORY",
                        "BS_BILLS_FROM_VENDORS",
                        "BS_SALES_TAX_COLLECTED",
                        "BS_CUSTOMER_CREDITS",
                        "BS_OTHER_LIABILITIES",
                        "BS_PROFIT_NOT_YET_CLOSED")
                .doesNotContainKeys("BS_OTHER_ASSETS", "BS_OTHER_EQUITY", "REVENUE");
        assertThat(bs.get("BS_IN_THE_BANK")).isEqualByComparingTo("519.00"); // 500 - 25 + 40 + 7 - 3
        assertThat(bs.get("BS_WAITING_TO_BE_DEPOSITED")).isEqualByComparingTo("512.50"); // 600 - 500 + 12.50 + 400
        assertThat(bs.get("BS_CUSTOMERS_OWE_YOU")).isEqualByComparingTo("1000.00");
        assertThat(bs.get("BS_INVENTORY")).isEqualByComparingTo("580.00");
        assertThat(bs.get("BS_BILLS_FROM_VENDORS")).isEqualByComparingTo("900.00");
        assertThat(bs.get("BS_SALES_TAX_COLLECTED")).isEqualByComparingTo("180.00");
        assertThat(bs.get("BS_CUSTOMER_CREDITS")).isEqualByComparingTo("12.50");
        assertThat(bs.get("BS_OTHER_LIABILITIES"))
                .as("2350 Settlement Suspense")
                .isEqualByComparingTo("40.00");
        // Revenue 1820 + 7 less expenses 300 + 20 + 25 + 3.
        assertThat(bs.get("BS_PROFIT_NOT_YET_CLOSED")).isEqualByComparingTo("1479.00");
        assertThat(balanceSheet.getTotalAssets()).isEqualByComparingTo("2611.50");
        assertThat(balanceSheet.getTotalLiabilities()).isEqualByComparingTo("1132.50");
        assertThat(balanceSheet.getTotalEquity()).isEqualByComparingTo("1479.00");
        assertThat(balanceSheet.getBalanced()).isTrue();

        IncomeStatementReport incomeStatement = financialReportingService.generateIncomeStatement(DAY, DAY);
        Map<String, BigDecimal> is = incomeStatement.getLineItems();

        assertThat(is)
                .containsOnlyKeys(
                        "IS_SALES",
                        "IS_COST_OF_PARTS_SOLD",
                        "IS_CARD_PROCESSING_FEES",
                        "IS_OTHER_INCOME",
                        "IS_OTHER_EXPENSES");
        assertThat(is.get("IS_SALES")).isEqualByComparingTo("1820.00");
        // 5000 Cost of Goods Sold + 5100 Inventory Shrinkage: shrinkage is cost of inventory consumed and sits above
        // gross margin, never on IS_OTHER_EXPENSES (Accounting Domain sign-off on #2545).
        assertThat(is.get("IS_COST_OF_PARTS_SOLD")).isEqualByComparingTo("320.00");
        assertThat(is.get("IS_CARD_PROCESSING_FEES")).isEqualByComparingTo("25.00");
        assertThat(is.get("IS_OTHER_INCOME")).as("4930 Cash Over").isEqualByComparingTo("7.00");
        assertThat(is.get("IS_OTHER_EXPENSES"))
                .as("6040 Cash Short (6115 before AW30)")
                .isEqualByComparingTo("3.00");
        assertThat(incomeStatement.getTotalRevenue()).isEqualByComparingTo("1827.00");
        assertThat(incomeStatement.getTotalExpenses()).isEqualByComparingTo("348.00");
        assertThat(incomeStatement.getNetIncome())
                .isEqualByComparingTo(incomeStatement.getTotalRevenue().subtract(incomeStatement.getTotalExpenses()))
                .isNotEqualByComparingTo(incomeStatement.getTotalRevenue());
    }

    @Test
    @DisplayName("AC2 (AW30): a REVENUE account such as 4940 Rubber Dust Sales with no named line is other income,"
            + " never an expense")
    void rubberDustSalesIsOtherIncome() {
        // The default tenant holds 4940 Rubber Dust Sales Income, V2's 6900 renumbered by AW30 (#2511), with a Labor
        // & Overhead line and no income-statement line.
        post(line("1000", "55.00", "0"), line("4940", "0", "55.00"));

        IncomeStatementReport report = financialReportingService.generateIncomeStatement(DAY, DAY);

        assertThat(report.getLineItems().get("IS_OTHER_INCOME")).isEqualByComparingTo("55.00");
        assertThat(report.getLineItems()).doesNotContainKey("IS_OTHER_EXPENSES");
        assertThat(report.getTotalRevenue()).isEqualByComparingTo("55.00");
        assertThat(report.getTotalExpenses()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("AC3: an account created through the GL account API lands on its type's Other line with code and"
            + " type in the drill-down, a second BANK_CASH account on BS_IN_THE_BANK, and balance-sheet drill-down"
            + " rows are as-of balances adding up to their line")
    void createdAccountsLandOnComputedLines() {
        glAccountService.createGLAccount(GLAccountCreateRequest.builder()
                .accountCode("1250")
                .accountName("GST Recoverable")
                .accountType(AccountType.ASSET)
                .accountSubtype(AccountSubtype.RECEIVABLE)
                .reconcilable(false)
                .build());
        glAccountService.createGLAccount(GLAccountCreateRequest.builder()
                .accountCode("1010")
                .accountName("Savings")
                .accountType(AccountType.ASSET)
                .accountSubtype(AccountSubtype.BANK_CASH)
                .reconcilable(true)
                .build());
        // Yesterday: cash in the bank; today: a transfer to savings and recoverable tax paid from the bank.
        post(DAY.minusDays(1), line("1000", "1000.00", "0"), line("4000", "0", "1000.00"));
        post(line("1010", "250.00", "0"), line("1000", "0", "250.00"));
        post(line("1250", "30.00", "0"), line("1000", "0", "30.00"));

        BalanceSheetReport balanceSheet = financialReportingService.generateBalanceSheet(DAY);
        assertThat(balanceSheet.getLineItems().get("BS_OTHER_ASSETS")).isEqualByComparingTo("30.00");
        assertThat(balanceSheet.getLineItems().get("BS_IN_THE_BANK")).isEqualByComparingTo("970.00"); // 720 + 250
        assertThat(balanceSheet.getBalanced()).isTrue();

        // The drill-down window starts today, yet the balance-sheet rows are as-of balances: the bank
        // line's 720 includes yesterday's 1000 and adds up to the line with the second bank account.
        List<AccountDrilldownResponse> bank = financialReportingService.drilldownToAccounts("BS_IN_THE_BANK", DAY, DAY);
        assertThat(bank).extracting(AccountDrilldownResponse::getAccountCode).containsExactly("1000", "1010");
        assertThat(bank).extracting(AccountDrilldownResponse::getAccountName).containsExactly("Cash", "Savings");
        assertThat(bank).extracting(AccountDrilldownResponse::getAccountType).containsOnly(AccountType.ASSET);
        assertThat(bank.get(0).getBalance()).isEqualByComparingTo("720.00");
        assertThat(bank.get(1).getBalance()).isEqualByComparingTo("250.00");
        assertThat(sum(bank)).isEqualByComparingTo(balanceSheet.getLineItems().get("BS_IN_THE_BANK"));

        List<AccountDrilldownResponse> otherAssets =
                financialReportingService.drilldownToAccounts("BS_OTHER_ASSETS", DAY, DAY);
        assertThat(otherAssets).singleElement().satisfies(row -> {
            assertThat(row.getAccountCode()).isEqualTo("1250");
            assertThat(row.getAccountName()).isEqualTo("GST Recoverable");
            assertThat(row.getAccountType()).isEqualTo(AccountType.ASSET);
            assertThat(row.getBalance()).isEqualByComparingTo("30.00");
        });
        assertThat(sum(otherAssets))
                .isEqualByComparingTo(balanceSheet.getLineItems().get("BS_OTHER_ASSETS"));

        // The profit line's drill-down lists the revenue account with its as-of balance.
        List<AccountDrilldownResponse> profit =
                financialReportingService.drilldownToAccounts("BS_PROFIT_NOT_YET_CLOSED", DAY, DAY);
        assertThat(profit).singleElement().satisfies(row -> {
            assertThat(row.getAccountCode()).isEqualTo("4000");
            assertThat(row.getAccountType()).isEqualTo(AccountType.REVENUE);
            assertThat(row.getBalance()).isEqualByComparingTo("1000.00");
        });
    }

    @Test
    @DisplayName("AC4: the seed applied twice leaves each account one line per statement type and no REVENUE line, in"
            + " the template and in the tenant")
    void seedAppliedTwiceIsIdempotent() throws SQLException {
        runSeed();
        TenantTestSupport.asTenant(TENANT, () -> provisioner.provision(TENANT, null, templateReader.snapshot()));

        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        for (Object tenant : List.of(PlatformTenant.ID, TENANT)) {
            Integer duplicates = owner.queryForObject(
                    "SELECT count(*) FROM (SELECT gl_account_id, statement_type FROM statement_line_mappings"
                            + " WHERE tenant_id = ? AND location_id IS NULL GROUP BY gl_account_id, statement_type"
                            + " HAVING count(*) > 1) d",
                    Integer.class,
                    tenant);
            assertThat(duplicates)
                    .as("tenant %s: one line per account per statement", tenant)
                    .isZero();
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM statement_line_mappings WHERE tenant_id = ?"
                                    + " AND statement_line_code = 'REVENUE'",
                            Integer.class,
                            tenant))
                    .as("tenant %s: no REVENUE line remains", tenant)
                    .isZero();
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM statement_line_mappings WHERE tenant_id = ?"
                                    + " AND statement_type = 'BALANCE_SHEET'",
                            Integer.class,
                            tenant))
                    .as(
                            "tenant %s: the eight balance-sheet mappings, 1080, 3000 and 3900 (#2511) and 2100 (#2509)",
                            tenant)
                    .isEqualTo(12);
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM statement_line_mappings WHERE tenant_id = ? AND statement_type ="
                                    + " 'INCOME_STATEMENT' AND statement_line_code = 'IS_COST_OF_PARTS_SOLD'",
                            Integer.class,
                            tenant))
                    .as("tenant %s: 5000, 5050, 5060 (#2509) and 5100 on cost of parts sold", tenant)
                    .isEqualTo(4);
        }
        assertThat(statementLineMappingRepository.findByStatementLineCode("IS_SALES"))
                .singleElement()
                .satisfies(line -> assertThat(line.getLineDescription()).isEqualTo("Sales"));
    }

    // ===== fixtures =====

    private record Line(String code, String debit, String credit) {}

    private static Line line(String code, String debit, String credit) {
        return new Line(code, debit, credit);
    }

    private void post(Line... lines) {
        post(DAY, lines);
    }

    private void post(LocalDate day, Line... lines) {
        JournalEntry entry = new JournalEntry();
        entry.setStatus(JournalEntryStatus.POSTED);
        entry.setEntryNumber(String.format("JE-S35-%06d", ENTRY_NUMBERS.incrementAndGet()));
        entry.setTransactionDate(LocalDateTime.of(day, java.time.LocalTime.NOON));
        entry.setSourceEventType("TEST_EVENT");
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        List<JournalEntryLine> entryLines = new ArrayList<>();
        for (Line line : lines) {
            GLAccount account =
                    glAccountRepository.findByAccountCode(line.code()).orElseThrow();
            JournalEntryLine entryLine = new JournalEntryLine();
            entryLine.setGlAccount(account);
            entryLine.setAccountCode(account.getAccountCode());
            entryLine.setAccountName(account.getAccountName());
            entryLine.setDebitAmount(new BigDecimal(line.debit()));
            entryLine.setCreditAmount(new BigDecimal(line.credit()));
            entryLine.setDescription("S35 " + line.code());
            entryLines.add(entryLine);
            debits = debits.add(entryLine.getDebitAmount());
            credits = credits.add(entryLine.getCreditAmount());
        }
        assertThat(debits).as("fixture entry balances").isEqualByComparingTo(credits);
        entryLines.forEach(entry::addLine);
        entry.setTotalDebits(debits);
        entry.setTotalCredits(credits);
        entry.setIsBalanced(true);
        journalEntryRepository.save(entry);
    }

    private static BigDecimal sum(List<AccountDrilldownResponse> rows) {
        return rows.stream().map(AccountDrilldownResponse::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static DataSource ownerDataSource() {
        return AccountingPostgresContainer.ownerDataSource(DATABASE);
    }

    /** Runs the repeatable seed the way Flyway does: as the owner, in one transaction. */
    private static void runSeed() throws SQLException {
        try (Connection connection = ownerDataSource().getConnection()) {
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(SEED));
            connection.commit();
        }
    }
}
