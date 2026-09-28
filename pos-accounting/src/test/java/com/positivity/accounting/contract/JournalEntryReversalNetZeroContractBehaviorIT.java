package com.positivity.accounting.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.dto.AccountDrilldownResponse;
import com.positivity.accounting.internal.dto.GeneralLedgerAccountSection;
import com.positivity.accounting.internal.dto.GeneralLedgerReport;
import com.positivity.accounting.internal.dto.IncomeStatementReport;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest.JournalEntryLineRequest;
import com.positivity.accounting.internal.dto.JournalLineDrilldownResponse;
import com.positivity.accounting.internal.dto.LaborOverheadCostReport;
import com.positivity.accounting.internal.dto.LaborOverheadReportLine;
import com.positivity.accounting.internal.dto.TrialBalanceReport;
import com.positivity.accounting.internal.dto.TrialBalanceRow;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.accounting.internal.service.FinancialReportingService;
import com.positivity.accounting.internal.service.GLAccountService;
import com.positivity.accounting.internal.service.JournalEntryService;
import com.positivity.accounting.internal.service.LaborOverheadReportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * A reversed journal entry and its reversal net to zero on every balance and report surface
 * (issue #2308). Reversal saves the reversal {@code POSTED} and flips the original to
 * {@code REVERSED}; every surface counts both statuses, each at its own transaction date, and
 * never counts {@code DRAFT}.
 *
 * <p>Entries are posted and reversed through {@link JournalEntryService} against the real
 * repository queries, so reverting the status predicate to {@code POSTED} only fails this test.
 */
@Transactional
class JournalEntryReversalNetZeroContractBehaviorIT extends BaseContractIntegrationTest {

    private static final String CASH_LINE = "ASSET_CASH_2308";
    private static final String REVENUE_LINE = "REVENUE_SALES_2308";
    private static final String FICA_LEAF = "1.3.1";
    private static final String LOCATION = "LOC-2308";

    private static final LocalDateTime ORIGINAL_DATE = LocalDateTime.of(2026, 8, 15, 10, 0);
    private static final LocalDate AUG_START = LocalDate.of(2026, 8, 1);
    private static final LocalDate AUG_END = LocalDate.of(2026, 8, 31);
    private static final LocalDate SEP_START = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_END = LocalDate.of(2026, 9, 30);

    @Autowired
    private JournalEntryService journalEntryService;

    @Autowired
    private FinancialReportingService reportingService;

    @Autowired
    private GLAccountService glAccountService;

    @Autowired
    private LaborOverheadReportService laborOverheadReportService;

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private StatementLineMappingRepository statementLineMappingRepository;

    private GLAccount cash;
    private GLAccount revenue;
    private GLAccount fica;
    private GLAccount taxPayable;

    @BeforeEach
    void setUp() {
        cash = saveAccount("1908", "Cash 2308", AccountType.ASSET);
        revenue = saveAccount("4908", "Sales 2308", AccountType.REVENUE);
        fica = saveAccount("6908", "FICA 2308", AccountType.EXPENSE);
        taxPayable = saveAccount("2200", "Sales Tax Payable", AccountType.LIABILITY);
        saveMapping(cash, StatementType.BALANCE_SHEET, CASH_LINE, null);
        saveMapping(revenue, StatementType.INCOME_STATEMENT, REVENUE_LINE, null);
        saveMapping(fica, StatementType.LABOR_OVERHEAD, FICA_LEAF, null);
    }

    @Test
    @DisplayName("a reversed entry and its reversal net to zero on every surface, and DRAFT never counts")
    void reversedPairNetsToZeroEverywhere() {
        UUID original = postOriginal();
        // A DRAFT in the same period must stay excluded everywhere.
        createEntry(ORIGINAL_DATE.plusDays(1), new BigDecimal("999.00"));

        journalEntryService.reverseJournalEntry(original, "net-zero check", LocalDate.of(2026, 8, 20));

        assertThat(glAccountService.getAccountBalance(cash.getGlAccountId()).getBalance())
                .as("GL account balance")
                .isEqualByComparingTo("0");

        assertThat(reportingService.generateBalanceSheet(AUG_END).getLineItems().get(CASH_LINE))
                .as("balance sheet")
                .isEqualByComparingTo("0");

        TrialBalanceRow cashRow = trialBalanceRow(reportingService.generateTrialBalance(AUG_END), cash);
        assertThat(cashRow.getTotalDebit()).as("trial balance debit").isEqualByComparingTo("100.00");
        assertThat(cashRow.getTotalCredit()).as("trial balance credit").isEqualByComparingTo("100.00");
        assertThat(cashRow.getBalance()).as("trial balance").isEqualByComparingTo("0");

        IncomeStatementReport august = reportingService.generateIncomeStatement(AUG_START, AUG_END);
        assertThat(august.getLineItems().get(REVENUE_LINE))
                .as("income statement")
                .isEqualByComparingTo("0");

        List<AccountDrilldownResponse> accounts =
                reportingService.drilldownToAccounts(REVENUE_LINE, AUG_START, AUG_END);
        assertThat(accounts)
                .singleElement()
                .satisfies(account -> assertThat(account.getBalance())
                        .as("account drill-down")
                        .isEqualByComparingTo("0"));

        List<JournalLineDrilldownResponse> cashLines =
                reportingService.drilldownToJournalLines(cash.getGlAccountId().toString(), AUG_START, AUG_END);
        assertThat(cashLines)
                .as("journal-line drill-down shows the original and its reversal")
                .hasSize(2);
        assertThat(cashLines.stream()
                        .map(line -> line.getDebitAmount().subtract(line.getCreditAmount()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0");

        GeneralLedgerAccountSection ledger = ledgerSection(
                reportingService.generateGeneralLedger(cash.getGlAccountId().toString(), AUG_START, AUG_END), cash);
        assertThat(ledger.getOpeningBalance()).as("GL opening").isEqualByComparingTo("0");
        assertThat(ledger.getLines()).as("GL lines").hasSize(2);
        assertThat(ledger.getLines().get(0).getRunningBalance())
                .as("GL running")
                .isEqualByComparingTo("100.00");
        assertThat(ledger.getLines().get(1).getRunningBalance()).isEqualByComparingTo("0");
        assertThat(ledger.getClosingBalance()).as("GL closing").isEqualByComparingTo("0");
        GeneralLedgerReport september =
                reportingService.generateGeneralLedger(cash.getGlAccountId().toString(), SEP_START, SEP_END);
        assertThat(september.getAccounts())
                .as("September opens at zero")
                .allSatisfy(section -> assertThat(section.getOpeningBalance()).isEqualByComparingTo("0"));

        assertThat(reportingService
                        .generateTaxLiability(AUG_START, AUG_END)
                        .getReconciliation()
                        .getGlNetActivity())
                .as("tax-liability GL drift column")
                .isEqualByComparingTo("0");

        assertThat(laborLine(laborOverheadReportService.generate(LOCATION, 2026, 12), FICA_LEAF)
                        .getMonthly()
                        .get(7))
                .as("labor and overhead report, August")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a later-period reversal leaves the original period's figures unchanged")
    void laterPeriodReversalKeepsHistory() {
        UUID original = postOriginal();
        BigDecimal augustRevenueBefore = reportingService
                .generateIncomeStatement(AUG_START, AUG_END)
                .getLineItems()
                .get(REVENUE_LINE);
        BigDecimal augustCashBefore =
                reportingService.generateBalanceSheet(AUG_END).getLineItems().get(CASH_LINE);
        assertThat(augustRevenueBefore).isEqualByComparingTo("-100.00");

        journalEntryService.reverseJournalEntry(original, "reversed in September", LocalDate.of(2026, 9, 5));

        assertThat(reportingService
                        .generateIncomeStatement(AUG_START, AUG_END)
                        .getLineItems()
                        .get(REVENUE_LINE))
                .as("August revenue after a September reversal")
                .isEqualByComparingTo(augustRevenueBefore);
        assertThat(reportingService.generateBalanceSheet(AUG_END).getLineItems().get(CASH_LINE))
                .as("August balance sheet after a September reversal")
                .isEqualByComparingTo(augustCashBefore);
        assertThat(reportingService
                        .generateIncomeStatement(SEP_START, SEP_END)
                        .getLineItems()
                        .get(REVENUE_LINE))
                .as("September carries the reversal")
                .isEqualByComparingTo("100.00");
        assertThat(ledgerSection(
                                reportingService.generateGeneralLedger(
                                        cash.getGlAccountId().toString(), SEP_START, SEP_END),
                                cash)
                        .getOpeningBalance())
                .as("September GL opening includes the August original")
                .isEqualByComparingTo("100.00");
        assertThat(reportingService.generateBalanceSheet(SEP_END).getLineItems().get(CASH_LINE))
                .as("net zero from the reversal's date onward")
                .isEqualByComparingTo("0");
    }

    /** Dr Cash 100 / Cr Sales 100, Dr FICA 40 (at LOCATION) / Cr Sales Tax Payable 40, posted. */
    private UUID postOriginal() {
        UUID id = createEntry(ORIGINAL_DATE, new BigDecimal("100.00"));
        journalEntryService.postJournalEntry(id);
        return id;
    }

    private UUID createEntry(LocalDateTime date, BigDecimal cashAmount) {
        JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                .organizationId(UUID.fromString("00000000-0000-0000-0000-000000002308"))
                .transactionDate(date)
                .description("Reversal net-zero fixture")
                .sourceEventType("TEST_2308")
                .lines(List.of(
                        line(cash, cashAmount, BigDecimal.ZERO, null),
                        line(revenue, BigDecimal.ZERO, cashAmount, null),
                        line(fica, new BigDecimal("40.00"), BigDecimal.ZERO, Map.of("locationId", LOCATION)),
                        line(taxPayable, BigDecimal.ZERO, new BigDecimal("40.00"), null)))
                .build();
        return journalEntryService.createJournalEntry(request).getJournalEntryId();
    }

    private static JournalEntryLineRequest line(
            GLAccount account, BigDecimal debit, BigDecimal credit, Map<String, String> dimensions) {
        return JournalEntryLineRequest.builder()
                .glAccountId(account.getGlAccountId())
                .debitAmount(debit)
                .creditAmount(credit)
                .description(account.getAccountName())
                .dimensions(dimensions)
                .build();
    }

    private GLAccount saveAccount(String code, String name, AccountType type) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(UUID.randomUUID());
        account.setAccountCode(code);
        account.setAccountName(name);
        account.setAccountType(type);
        account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        account.setCreatedBy("TEST");
        account.setModifiedBy("TEST");
        return glAccountRepository.save(account);
    }

    private void saveMapping(GLAccount account, StatementType type, String lineCode, String locationId) {
        statementLineMappingRepository.save(StatementLineMapping.builder()
                .glAccount(account)
                .accountName(account.getAccountName())
                .statementType(type)
                .statementLineCode(lineCode)
                .lineDescription(lineCode)
                .displayOrder(1)
                .operation(OperationType.SUM)
                .locationId(locationId)
                .build());
    }

    private static TrialBalanceRow trialBalanceRow(TrialBalanceReport report, GLAccount account) {
        return report.getRows().stream()
                .filter(row -> account.getGlAccountId().toString().equals(row.getAccountId()))
                .findFirst()
                .orElseThrow();
    }

    private static GeneralLedgerAccountSection ledgerSection(GeneralLedgerReport report, GLAccount account) {
        return report.getAccounts().stream()
                .filter(section -> account.getGlAccountId().toString().equals(section.getAccountId()))
                .findFirst()
                .orElseThrow();
    }

    private static LaborOverheadReportLine laborLine(LaborOverheadCostReport report, String code) {
        return report.getLines().stream()
                .filter(line -> code.equals(line.getCode()))
                .findFirst()
                .orElseThrow();
    }
}
