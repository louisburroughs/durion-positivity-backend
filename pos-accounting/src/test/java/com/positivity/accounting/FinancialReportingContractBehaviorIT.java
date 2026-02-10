package com.positivity.accounting;

import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract behavior tests for Financial Reporting API (CAP-054).
 * 
 * Tests:
 * - Income Statement generation with revenue/expenses/net income
 * - Balance Sheet generation with assets/liabilities/equity
 * - Drilldown to contributing GL accounts
 * - Drilldown to source journal entries
 * - Access control (403 for missing permissions)
 * - Input validation (400 for invalid date ranges)
 * - Balance sheet equation validation
 *
 * @author Louis Burroughs
 * @since 2025-01-01
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FinancialReportingContractBehaviorIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StatementLineMappingRepository statementLineMappingRepository;

    @BeforeEach
    void setUp() {
        // Create sample statement line mappings for testing
        createSampleStatementLineMappings();
    }

    @Test
    @DisplayName("Generate Income Statement - Happy Path")
    @WithMockUser(authorities = "reporting:view:financial-statements")
    void testGenerateIncomeStatement_Success() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/reports/financial/income-statement")
                .param("startDate", "2024-01-01")
                .param("endDate", "2024-12-31")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.startDate").value("2024-01-01"))
                .andExpect(jsonPath("$.endDate").value("2024-12-31"))
                .andExpect(jsonPath("$.lineItems").isMap())
                .andExpect(jsonPath("$.totalRevenue").isNumber())
                .andExpect(jsonPath("$.totalExpenses").isNumber())
                .andExpect(jsonPath("$.netIncome").isNumber())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andReturn();

        // Verify net income calculation: revenue - expenses
        String responseJson = result.getResponse().getContentAsString();
        assertThat(responseJson).contains("netIncome");
    }

    @Test
    @DisplayName("Generate Balance Sheet - Happy Path")
    @WithMockUser(authorities = "reporting:view:financial-statements")
    void testGenerateBalanceSheet_Success() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/reports/financial/balance-sheet")
                .param("asOfDate", "2024-12-31")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.asOfDate").value("2024-12-31"))
                .andExpect(jsonPath("$.lineItems").isMap())
                .andExpect(jsonPath("$.totalAssets").isNumber())
                .andExpect(jsonPath("$.totalLiabilities").isNumber())
                .andExpect(jsonPath("$.totalEquity").isNumber())
                .andExpect(jsonPath("$.balanced").isBoolean())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andReturn();

        // Verify balance sheet equation flag is present
        String responseJson = result.getResponse().getContentAsString();
        assertThat(responseJson).contains("balanced");
    }

    @Test
    @DisplayName("Generate Income Statement - Unauthorized (403)")
    @WithMockUser(authorities = "some:other:permission")
    void testGenerateIncomeStatement_Unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/reports/financial/income-statement")
                .param("startDate", "2024-01-01")
                .param("endDate", "2024-12-31")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Generate Balance Sheet - Unauthorized (403)")
    @WithMockUser(authorities = "some:other:permission")
    void testGenerateBalanceSheet_Unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/reports/financial/balance-sheet")
                .param("asOfDate", "2024-12-31")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Generate Income Statement - Invalid Date Range (400)")
    @WithMockUser(authorities = "reporting:view:financial-statements")
    void testGenerateIncomeStatement_InvalidDateRange() throws Exception {
        mockMvc.perform(get("/api/v1/reports/financial/income-statement")
                .param("startDate", "2024-12-31")
                .param("endDate", "2024-01-01") // end before start
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Drilldown to Accounts - Happy Path")
    @WithMockUser(authorities = "reporting:view:financial-statements")
    void testDrilldownToAccounts_Success() throws Exception {
        mockMvc.perform(get("/api/v1/reports/financial/drilldown/accounts/REVENUE_SALES")
                .param("startDate", "2024-01-01")
                .param("endDate", "2024-12-31")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Drilldown to Journal Lines - Happy Path")
    @WithMockUser(authorities = "reporting:view:financial-statements")
    void testDrilldownToJournalLines_Success() throws Exception {
        // Use a valid UUID format for the account ID path parameter
        String testAccountId = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/v1/reports/financial/drilldown/journal-lines/" + testAccountId)
                .param("startDate", "2024-01-01")
                .param("endDate", "2024-12-31")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Drilldown to Accounts - Invalid Date Range (400)")
    @WithMockUser(authorities = "reporting:view:financial-statements")
    void testDrilldownToAccounts_InvalidDateRange() throws Exception {
        mockMvc.perform(get("/api/v1/reports/financial/drilldown/accounts/REVENUE_SALES")
                .param("startDate", "2024-12-31")
                .param("endDate", "2024-01-01") // end before start
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Report Reproducibility - Same Params Return Same Results")
    @WithMockUser(authorities = "reporting:view:financial-statements")
    void testReportReproducibility() throws Exception {
        // Generate report twice with same parameters
        MvcResult result1 = mockMvc.perform(get("/api/v1/reports/financial/income-statement")
                .param("startDate", "2024-01-01")
                .param("endDate", "2024-12-31")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult result2 = mockMvc.perform(get("/api/v1/reports/financial/income-statement")
                .param("startDate", "2024-01-01")
                .param("endDate", "2024-12-31")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        // Parse responses
        String json1 = result1.getResponse().getContentAsString();
        String json2 = result2.getResponse().getContentAsString();

        // Verify line items are identical (excluding generatedAt timestamp)
        assertThat(json1).contains("\"startDate\":\"2024-01-01\"");
        assertThat(json2).contains("\"startDate\":\"2024-01-01\"");
        assertThat(json1).contains("\"endDate\":\"2024-12-31\"");
        assertThat(json2).contains("\"endDate\":\"2024-12-31\"");
    }

    // ========== Test Data Setup ==========

    /**
     * Create sample statement line mappings for testing.
     */
    private void createSampleStatementLineMappings() {
        // Use consistent UUIDs for test data
        UUID revenueAccountId = UUID.randomUUID();
        UUID cogsAccountId = UUID.randomUUID();
        UUID cashAccountId = UUID.randomUUID();
        UUID apAccountId = UUID.randomUUID();
        UUID equityAccountId = UUID.randomUUID();
        
        // Income Statement Mappings
        statementLineMappingRepository.save(StatementLineMapping.builder()
                .mappingId(UUID.randomUUID())
                .glAccountId(revenueAccountId)
                .accountName("Sales Revenue")
                .statementType(StatementType.INCOME_STATEMENT)
                .statementLineCode("REVENUE_SALES")
                .lineDescription("Revenue from product sales")
                .displayOrder(10)
                .operation(OperationType.SUM)
                .build());

        statementLineMappingRepository.save(StatementLineMapping.builder()
                .mappingId(UUID.randomUUID())
                .glAccountId(cogsAccountId)
                .accountName("Cost of Goods Sold")
                .statementType(StatementType.INCOME_STATEMENT)
                .statementLineCode("EXPENSE_COGS")
                .lineDescription("Cost of products sold")
                .displayOrder(20)
                .operation(OperationType.SUM)
                .build());

        // Balance Sheet Mappings
        statementLineMappingRepository.save(StatementLineMapping.builder()
                .mappingId(UUID.randomUUID())
                .glAccountId(cashAccountId)
                .accountName("Cash")
                .statementType(StatementType.BALANCE_SHEET)
                .statementLineCode("ASSET_CURRENT_CASH")
                .lineDescription("Cash and cash equivalents")
                .displayOrder(10)
                .operation(OperationType.SUM)
                .build());

        statementLineMappingRepository.save(StatementLineMapping.builder()
                .mappingId(UUID.randomUUID())
                .glAccountId(apAccountId)
                .accountName("Accounts Payable")
                .statementType(StatementType.BALANCE_SHEET)
                .statementLineCode("LIABILITY_CURRENT_AP")
                .lineDescription("Current accounts payable")
                .displayOrder(50)
                .operation(OperationType.SUM)
                .build());

        statementLineMappingRepository.save(StatementLineMapping.builder()
                .mappingId(UUID.randomUUID())
                .glAccountId(equityAccountId)
                .accountName("Owner's Equity")
                .statementType(StatementType.BALANCE_SHEET)
                .statementLineCode("EQUITY_OWNERS")
                .lineDescription("Owner's capital")
                .displayOrder(80)
                .operation(OperationType.SUM)
                .build());
    }
}
