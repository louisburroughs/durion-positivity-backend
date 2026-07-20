package com.positivity.accounting.internal.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.AgedPayablesReport;
import com.positivity.accounting.internal.dto.AgedReceivablesReport;
import com.positivity.accounting.internal.dto.AgingSummary;
import com.positivity.accounting.internal.dto.GeneralLedgerReport;
import com.positivity.accounting.internal.dto.TrialBalanceReport;
import com.positivity.accounting.service.FinancialReportingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Unit tests for FinancialReportingController input validation.
 *
 * Focuses on security validation (S5145) to prevent log injection attacks
 * via untrusted path variables like statementLineCode.
 */
@DisplayName("FinancialReportingController Input Validation Tests")
class FinancialReportingControllerTest extends BaseIntegrationTest {

    @MockitoBean
    private FinancialReportingService financialReportingService;

    private static final LocalDate START_DATE = LocalDate.of(2024, 1, 1);
    private static final LocalDate END_DATE = LocalDate.of(2024, 12, 31);

    @Nested
    @DisplayName("Drilldown to Accounts - statementLineCode Validation (S5145)")
    class DrilldownToAccountsValidation {

        @Test
        @DisplayName("Should accept valid statement line code (uppercase with underscores)")
        void shouldAcceptValidStatementLineCode() throws Exception {
            // Given: Valid statement line code
            String validCode = "REVENUE_SALES";
            when(financialReportingService.drilldownToAccounts(eq(validCode), any(), any()))
                    .thenReturn(Collections.emptyList());

            // When: Request with valid code
            mockMvc.perform(get("/v1/accounting/reports/financial/drilldown/accounts/{statementLineCode}", validCode)
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("startDate", START_DATE.toString())
                            .param("endDate", END_DATE.toString()))
                    // Then: Should succeed
                    .andExpect(status().isOk());

            verify(financialReportingService).drilldownToAccounts(eq(validCode), any(), any());
        }

        @Test
        @DisplayName("Should reject lowercase statement line code")
        void shouldRejectLowercaseStatementLineCode() throws Exception {
            // Given: Invalid code with lowercase letters
            String invalidCode = "revenue_sales";

            // When: Request with invalid code
            mockMvc.perform(get("/v1/accounting/reports/financial/drilldown/accounts/{statementLineCode}", invalidCode)
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("startDate", START_DATE.toString())
                            .param("endDate", END_DATE.toString()))
                    // Then: Should return 400 Bad Request
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message", containsString("Invalid statement line code format")));

            // Service should not be called with invalid input
            verify(financialReportingService, never()).drilldownToAccounts(any(), any(), any());
        }

        @Test
        @DisplayName("Should reject CRLF injection attempt (security S5145) - blocked by Spring Security firewall")
        void shouldRejectCRLFInjection() throws Exception {
            // Given: Malicious code with newline characters (URL-encoded)
            // Note: Spring Security's StrictHttpFirewall blocks requests with URL-encoded
            // control characters
            String maliciousCode = "REVENUE%0A[FAKE_LOG_ENTRY]";

            // When: Request with CRLF injection
            // Then: Spring Security firewall blocks the request before it reaches
            // controller
            // This provides defense-in-depth alongside our controller validation
            mockMvc.perform(get(
                                    "/v1/accounting/reports/financial/drilldown/accounts/{statementLineCode}",
                                    maliciousCode)
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("startDate", START_DATE.toString())
                            .param("endDate", END_DATE.toString()))
                    // Firewall returns 400 Bad Request for blocked URLs
                    .andExpect(status().isBadRequest());

            verify(financialReportingService, never()).drilldownToAccounts(any(), any(), any());
        }

        @Test
        @DisplayName("Should reject special characters in statement line code")
        void shouldRejectSpecialCharacters() throws Exception {
            // Given: Code with special characters
            String invalidCode = "REVENUE$SALES";

            // When: Request with special characters
            mockMvc.perform(get("/v1/accounting/reports/financial/drilldown/accounts/{statementLineCode}", invalidCode)
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("startDate", START_DATE.toString())
                            .param("endDate", END_DATE.toString()))
                    // Then: Should return 400 Bad Request
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verify(financialReportingService, never()).drilldownToAccounts(any(), any(), any());
        }

        @Test
        @DisplayName("Should reject statement line code exceeding 100 characters")
        void shouldRejectExcessiveLength() throws Exception {
            // Given: Code exceeding max length
            String tooLongCode = "A".repeat(101);

            // When: Request with excessive length
            mockMvc.perform(get("/v1/accounting/reports/financial/drilldown/accounts/{statementLineCode}", tooLongCode)
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("startDate", START_DATE.toString())
                            .param("endDate", END_DATE.toString()))
                    // Then: Should return 400 Bad Request
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verify(financialReportingService, never()).drilldownToAccounts(any(), any(), any());
        }

        @Test
        @DisplayName("Should accept statement line code at max length (100 chars)")
        void shouldAcceptMaxLength() throws Exception {
            // Given: Code at max length
            String maxLengthCode = "A".repeat(100);
            when(financialReportingService.drilldownToAccounts(eq(maxLengthCode), any(), any()))
                    .thenReturn(Collections.emptyList());

            // When: Request with max length code
            mockMvc.perform(get(
                                    "/v1/accounting/reports/financial/drilldown/accounts/{statementLineCode}",
                                    maxLengthCode)
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("startDate", START_DATE.toString())
                            .param("endDate", END_DATE.toString()))
                    // Then: Should succeed
                    .andExpect(status().isOk());

            verify(financialReportingService).drilldownToAccounts(eq(maxLengthCode), any(), any());
        }

        @Test
        @DisplayName("Should accept common valid statement line codes")
        void shouldAcceptCommonValidCodes() throws Exception {
            // Test multiple common valid codes
            String[] validCodes = {
                "REVENUE_SALES",
                "EXPENSE_COGS",
                "ASSET_CURRENT_CASH",
                "LIABILITY_CURRENT_AP",
                "EQUITY_OWNERS",
                "PL_REVENUE_TOTAL",
                "BS_ASSET_TOTAL"
            };

            for (String code : validCodes) {
                when(financialReportingService.drilldownToAccounts(eq(code), any(), any()))
                        .thenReturn(Collections.emptyList());

                mockMvc.perform(get("/v1/accounting/reports/financial/drilldown/accounts/{statementLineCode}", code)
                                .header("X-Authorities", "reporting:view:financial-statements")
                                .header("X-User", "test-user")
                                .param("startDate", START_DATE.toString())
                                .param("endDate", END_DATE.toString()))
                        .andExpect(status().isOk());
            }
        }
    }

    @Nested
    @DisplayName("Date Range Validation")
    class DateRangeValidation {

        @Test
        @DisplayName("Should reject end date before start date")
        void shouldRejectInvalidDateRange() throws Exception {
            // Given: End date before start date
            LocalDate invalidStartDate = LocalDate.of(2024, 12, 31);
            LocalDate invalidEndDate = LocalDate.of(2024, 1, 1);

            // When: Request with invalid date range
            mockMvc.perform(get(
                                    "/v1/accounting/reports/financial/drilldown/accounts/{statementLineCode}",
                                    "REVENUE_SALES")
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("startDate", invalidStartDate.toString())
                            .param("endDate", invalidEndDate.toString()))
                    // Then: Should return 400 Bad Request
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message", containsString("End date cannot be before")));

            verify(financialReportingService, never()).drilldownToAccounts(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Trial Balance - asOf Validation (G1, #956)")
    class TrialBalanceValidation {

        @Test
        @DisplayName("Should generate trial balance for a valid asOf date")
        void shouldGenerateTrialBalanceForValidAsOf() throws Exception {
            // Given: Service returns an empty balanced report
            LocalDate asOf = LocalDate.of(2026, 6, 30);
            when(financialReportingService.generateTrialBalance(eq(asOf)))
                    .thenReturn(TrialBalanceReport.builder()
                            .asOfDate(asOf)
                            .generatedAt(Instant.parse("2026-07-01T00:00:00Z"))
                            .rows(Collections.emptyList())
                            .totalDebit(BigDecimal.ZERO)
                            .totalCredit(BigDecimal.ZERO)
                            .balanced(true)
                            .entryNumberGaps(Collections.emptyList())
                            .build());

            // When: Request with valid asOf date
            mockMvc.perform(get("/v1/accounting/reports/financial/trial-balance")
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("asOf", asOf.toString()))
                    // Then: Should succeed with the report body
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.asOfDate").value("2026-06-30"))
                    .andExpect(jsonPath("$.balanced").value(true))
                    .andExpect(jsonPath("$.rows").isEmpty())
                    .andExpect(jsonPath("$.entryNumberGaps").isEmpty());

            verify(financialReportingService).generateTrialBalance(eq(asOf));
        }

        @Test
        @DisplayName("Should reject malformed asOf date")
        void shouldRejectMalformedAsOf() throws Exception {
            // When: Request with a non-ISO date
            mockMvc.perform(get("/v1/accounting/reports/financial/trial-balance")
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("asOf", "06/30/2026"))
                    // Then: Spring binding rejects it before the service is reached
                    .andExpect(status().isBadRequest());

            verify(financialReportingService, never()).generateTrialBalance(any());
        }

        @Test
        @DisplayName("Should reject missing asOf date")
        void shouldRejectMissingAsOf() throws Exception {
            // When: Request without the required asOf parameter
            mockMvc.perform(get("/v1/accounting/reports/financial/trial-balance")
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user"))
                    // Then: Should return 400 Bad Request
                    .andExpect(status().isBadRequest());

            verify(financialReportingService, never()).generateTrialBalance(any());
        }
    }

    @Nested
    @DisplayName("General Ledger - endpoint (G2, #960)")
    class GeneralLedgerEndpoint {

        @Test
        @DisplayName("Should return the general ledger for a valid date range (all accounts)")
        void shouldGenerateGeneralLedger() throws Exception {
            when(financialReportingService.generateGeneralLedger(eq(null), eq(START_DATE), eq(END_DATE)))
                    .thenReturn(GeneralLedgerReport.builder()
                            .accountId(null)
                            .startDate(START_DATE)
                            .endDate(END_DATE)
                            .generatedAt(Instant.parse("2026-07-01T00:00:00Z"))
                            .accounts(Collections.emptyList())
                            .totalDebit(BigDecimal.ZERO)
                            .totalCredit(BigDecimal.ZERO)
                            .build());

            mockMvc.perform(get("/v1/accounting/reports/financial/general-ledger")
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("startDate", START_DATE.toString())
                            .param("endDate", END_DATE.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.startDate").value("2024-01-01"))
                    .andExpect(jsonPath("$.endDate").value("2024-12-31"))
                    .andExpect(jsonPath("$.accounts").isEmpty());

            verify(financialReportingService).generateGeneralLedger(eq(null), eq(START_DATE), eq(END_DATE));
        }

        @Test
        @DisplayName("Should reject a non-UUID accountId (400) before reaching the service")
        void shouldRejectInvalidAccountId() throws Exception {
            mockMvc.perform(get("/v1/accounting/reports/financial/general-ledger")
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("accountId", "not-a-uuid")
                            .param("startDate", START_DATE.toString())
                            .param("endDate", END_DATE.toString()))
                    .andExpect(status().isBadRequest());

            verify(financialReportingService, never()).generateGeneralLedger(any(), any(), any());
        }

        @Test
        @DisplayName("Should reject end date before start date (400)")
        void shouldRejectInvalidDateRange() throws Exception {
            mockMvc.perform(get("/v1/accounting/reports/financial/general-ledger")
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("startDate", END_DATE.toString())
                            .param("endDate", START_DATE.toString()))
                    .andExpect(status().isBadRequest());

            verify(financialReportingService, never()).generateGeneralLedger(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Aged Receivables / Aged Payables - endpoints (G2, #960)")
    class AgedReportEndpoints {

        private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

        private AgingSummary zeroTotals() {
            return AgingSummary.builder()
                    .current(BigDecimal.ZERO)
                    .days31To60(BigDecimal.ZERO)
                    .days61To90(BigDecimal.ZERO)
                    .days90Plus(BigDecimal.ZERO)
                    .totalOutstanding(BigDecimal.ZERO)
                    .build();
        }

        @Test
        @DisplayName("Should return aged receivables for a valid asOfDate")
        void shouldGenerateAgedReceivables() throws Exception {
            when(financialReportingService.generateAgedReceivables(eq(AS_OF)))
                    .thenReturn(AgedReceivablesReport.builder()
                            .asOfDate(AS_OF)
                            .generatedAt(Instant.parse("2026-07-01T00:00:00Z"))
                            .rows(Collections.emptyList())
                            .totals(zeroTotals())
                            .build());

            mockMvc.perform(get("/v1/accounting/reports/financial/aged-receivables")
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("asOfDate", AS_OF.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.asOfDate").value("2026-06-30"))
                    .andExpect(jsonPath("$.rows").isEmpty())
                    .andExpect(jsonPath("$.totals.totalOutstanding").value(0));

            verify(financialReportingService).generateAgedReceivables(eq(AS_OF));
        }

        @Test
        @DisplayName("Should reject missing asOfDate for aged receivables (400)")
        void shouldRejectMissingAsOfReceivables() throws Exception {
            mockMvc.perform(get("/v1/accounting/reports/financial/aged-receivables")
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user"))
                    .andExpect(status().isBadRequest());

            verify(financialReportingService, never()).generateAgedReceivables(any());
        }

        @Test
        @DisplayName("Should return aged payables for a valid asOfDate")
        void shouldGenerateAgedPayables() throws Exception {
            when(financialReportingService.generateAgedPayables(eq(AS_OF)))
                    .thenReturn(AgedPayablesReport.builder()
                            .asOfDate(AS_OF)
                            .generatedAt(Instant.parse("2026-07-01T00:00:00Z"))
                            .rows(Collections.emptyList())
                            .totals(zeroTotals())
                            .build());

            mockMvc.perform(get("/v1/accounting/reports/financial/aged-payables")
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("asOfDate", AS_OF.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.asOfDate").value("2026-06-30"))
                    .andExpect(jsonPath("$.rows").isEmpty())
                    .andExpect(jsonPath("$.totals.totalOutstanding").value(0));

            verify(financialReportingService).generateAgedPayables(eq(AS_OF));
        }

        @Test
        @DisplayName("Should reject malformed asOfDate for aged payables (400)")
        void shouldRejectMalformedAsOfPayables() throws Exception {
            mockMvc.perform(get("/v1/accounting/reports/financial/aged-payables")
                            .header("X-Authorities", "reporting:view:financial-statements")
                            .header("X-User", "test-user")
                            .param("asOfDate", "06/30/2026"))
                    .andExpect(status().isBadRequest());

            verify(financialReportingService, never()).generateAgedPayables(any());
        }
    }
}
