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
import com.positivity.accounting.service.FinancialReportingService;
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
}
