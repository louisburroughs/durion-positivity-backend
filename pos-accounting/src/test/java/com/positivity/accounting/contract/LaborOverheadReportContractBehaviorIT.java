package com.positivity.accounting.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.dto.LaborOverheadCostReport;
import com.positivity.accounting.internal.dto.LaborOverheadReportLine;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.report.LaborOverheadTaxonomy;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Provider behavioral contract test (CAP-316): proves the Labor &amp; Overhead report amounts trace to
 * posted GL data for a seeded location/period, and that subtotals roll up from the contributing leaf.
 */
@DisplayName("Labor & Overhead Report ContractBehaviorIT")
class LaborOverheadReportContractBehaviorIT extends BaseContractIntegrationTest {

    private static final String PATH = "/v1/accounting/reports/location/labor-overhead";
    private static final String LOCATION = "LOC-IT-316";
    private static final int FISCAL_YEAR = 2026;

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private StatementLineMappingRepository statementLineMappingRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Test
    @DisplayName("Report leaf + subtotal amounts trace to a posted journal entry for the location")
    void reportAmountsTraceToPostedGl() throws Exception {
        GLAccount account = seedAccount("6010-IT", "Retread Plant Hourly Wages (IT)");
        seedMapping("1.1.1", account);
        // Posted expense in March for the target location.
        seedPostedExpense(account, 3, LOCATION, new BigDecimal("1234.56"));
        // A different location must not leak into this report.
        seedPostedExpense(account, 3, "LOC-OTHER", new BigDecimal("9999.00"));

        String json = mockMvc.perform(withAuth(get(PATH)
                        .param("locationId", LOCATION)
                        .param("fiscalYear", String.valueOf(FISCAL_YEAR))
                        .param("asOfMonth", "12")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        LaborOverheadCostReport report = objectMapper.readValue(json, LaborOverheadCostReport.class);
        assertThat(report.getLocationId()).isEqualTo(LOCATION);
        assertThat(report.getLines()).hasSameSizeAs(LaborOverheadTaxonomy.lines());

        LaborOverheadReportLine wages = line(report, "1.1.1");
        assertThat(wages.getMonthly().get(2)).isEqualByComparingTo("1234.56"); // March
        assertThat(wages.getMonthly().get(0)).isEqualByComparingTo("0");
        assertThat(wages.getYtd()).isEqualByComparingTo("1234.56");

        // Subtotal 1.1 and Total Labor roll up from the single contributing leaf.
        assertThat(line(report, "1.1").getYtd()).isEqualByComparingTo("1234.56");
        assertThat(line(report, LaborOverheadTaxonomy.TOTAL_LABOR).getYtd()).isEqualByComparingTo("1234.56");
        assertThat(line(report, LaborOverheadTaxonomy.TOTAL_LABOR_OVERHEAD).getYtd())
                .isEqualByComparingTo("1234.56");
        // An unmapped line stays present at zero.
        assertThat(line(report, "2.5").getYtd()).isEqualByComparingTo("0");
    }

    private GLAccount seedAccount(String code, String name) {
        GLAccount account = new GLAccount();
        account.setAccountCode(code);
        account.setAccountName(name);
        account.setAccountType(AccountType.EXPENSE);
        account.setCreatedBy("contract-test");
        account.setModifiedBy("contract-test");
        return glAccountRepository.save(account);
    }

    private void seedMapping(String lineCode, GLAccount account) {
        StatementLineMapping mapping = new StatementLineMapping();
        mapping.setStatementType(StatementType.LABOR_OVERHEAD);
        mapping.setStatementLineCode(lineCode);
        mapping.setGlAccount(account);
        mapping.setAccountName(account.getAccountCode());
        mapping.setOperation(OperationType.SUM);
        mapping.setDisplayOrder(1);
        statementLineMappingRepository.save(mapping);
    }

    private void seedPostedExpense(GLAccount account, int month, String location, BigDecimal amount) {
        JournalEntry entry = new JournalEntry();
        entry.setStatus(JournalEntryStatus.POSTED);
        entry.setTransactionDate(LocalDateTime.of(FISCAL_YEAR, month, 15, 0, 0));
        entry.setTotalDebits(amount);
        entry.setTotalCredits(amount);
        entry.setIsBalanced(true);

        JournalEntryLine line = new JournalEntryLine();
        line.setGlAccount(account);
        line.setAccountCode(account.getAccountCode());
        line.setDebitAmount(amount);
        line.setCreditAmount(BigDecimal.ZERO);
        line.setDimensions(Map.of("locationId", location));
        entry.addLine(line);

        journalEntryRepository.save(entry);
    }

    private static LaborOverheadReportLine line(LaborOverheadCostReport report, String code) {
        return report.getLines().stream()
                .filter(reportLine -> reportLine.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing line " + code));
    }
}
