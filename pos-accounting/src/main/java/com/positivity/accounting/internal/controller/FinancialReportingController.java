package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.AccountDrilldownResponse;
import com.positivity.accounting.internal.dto.BalanceSheetReport;
import com.positivity.accounting.internal.dto.IncomeStatementReport;
import com.positivity.accounting.internal.dto.JournalLineDrilldownResponse;
import com.positivity.accounting.service.FinancialReportingService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.NonNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * REST API for financial reporting (Income Statement, Balance Sheet,
 * drilldowns).
 * 
 * Provides GAAP-aligned financial statements with drilldown capability to
 * source transactions.
 *
 * @author Louis Burroughs
 * @since 2025-01-01
 */
@RestController
@RequestMapping("/api/v1/reports/financial")
@Tag(name = "Financial Reporting", description = "Income Statement and Balance Sheet generation with drilldown")
@SecurityRequirement(name = "bearerAuth")
public class FinancialReportingController {

    private final FinancialReportingService financialReportingService;

    public FinancialReportingController(FinancialReportingService financialReportingService) {
        this.financialReportingService = financialReportingService;
    }

    /**
     * Generate Income Statement (Profit & Loss) for a date range.
     */
    @GetMapping(value = "/income-statement", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('reporting:view:financial-statements')")
    @EmitEvent(id = "REPORT_INCOME_STATEMENT_GENERATE", apiVersion = "1")
    @Operation(summary = "Generate Income Statement", description = "Generate Profit & Loss report for a date range with revenue, expenses, and net income")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Income statement generated successfully", content = @Content(schema = @Schema(implementation = IncomeStatementReport.class))),
            @ApiResponse(responseCode = "400", description = "Invalid date range"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements")
    })
    public ResponseEntity<IncomeStatementReport> generateIncomeStatement(
            @Parameter(description = "Period start date (YYYY-MM-DD)", required = true, example = "2024-01-01") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @NonNull LocalDate startDate,

            @Parameter(description = "Period end date (YYYY-MM-DD)", required = true, example = "2024-12-31") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @NonNull LocalDate endDate) {

        // Use IllegalArgumentException for validation errors to leverage the module's
        // @RestControllerAdvice (APPaymentExceptionHandler) which maps it to 400 Bad Request
        // with a consistent ErrorResponse format (errorCode: "VALIDATION_ERROR").
        // This maintains the accounting domain's standard error contract across all endpoints.
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        IncomeStatementReport report = financialReportingService.generateIncomeStatement(startDate, endDate);
        return ResponseEntity.ok(report);
    }

    /**
     * Generate Balance Sheet as of a specific date.
     */
    @GetMapping(value = "/balance-sheet", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('reporting:view:financial-statements')")
    @EmitEvent(id = "REPORT_BALANCE_SHEET_GENERATE", apiVersion = "1")
    @Operation(summary = "Generate Balance Sheet", description = "Generate Balance Sheet as of a specific date with assets, liabilities, and equity")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Balance sheet generated successfully", content = @Content(schema = @Schema(implementation = BalanceSheetReport.class))),
            @ApiResponse(responseCode = "400", description = "Invalid date"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements")
    })
    public ResponseEntity<BalanceSheetReport> generateBalanceSheet(
            @Parameter(description = "As-of date (YYYY-MM-DD)", required = true, example = "2024-12-31") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @NonNull LocalDate asOfDate) {

        BalanceSheetReport report = financialReportingService.generateBalanceSheet(asOfDate);
        return ResponseEntity.ok(report);
    }

    /**
     * Drill down from statement line to contributing GL accounts.
     */
    @GetMapping(value = "/drilldown/accounts/{statementLineCode}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('reporting:view:financial-statements')")
    @EmitEvent(id = "REPORT_DRILLDOWN_ACCOUNTS", apiVersion = "1")
    @Operation(summary = "Drilldown to Accounts", description = "Show which GL accounts contribute to a specific statement line")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Account drilldown successful", 
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = AccountDrilldownResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Invalid statement line code or date range"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements")
    })
    public ResponseEntity<List<AccountDrilldownResponse>> drilldownToAccounts(
            @Parameter(description = "Statement line code (e.g., REVENUE_SALES)", required = true) @PathVariable @NonNull String statementLineCode,

            @Parameter(description = "Period start date (YYYY-MM-DD)", required = true, example = "2024-01-01") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @NonNull LocalDate startDate,

            @Parameter(description = "Period end date (YYYY-MM-DD)", required = true, example = "2024-12-31") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @NonNull LocalDate endDate) {

        // Use IllegalArgumentException for validation errors to leverage the module's
        // @RestControllerAdvice (APPaymentExceptionHandler) which maps it to 400 Bad Request
        // with a consistent ErrorResponse format (errorCode: "VALIDATION_ERROR").
        // This maintains the accounting domain's standard error contract across all endpoints.
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        List<AccountDrilldownResponse> response = financialReportingService.drilldownToAccounts(
                statementLineCode, startDate, endDate);
        return ResponseEntity.ok(response);
    }

    /**
     * Drill down from GL account to source journal lines.
     */
    @GetMapping(value = "/drilldown/journal-lines/{accountId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('reporting:view:financial-statements')")
    @EmitEvent(id = "REPORT_DRILLDOWN_JOURNAL_LINES", apiVersion = "1")
    @Operation(summary = "Drilldown to Journal Lines", description = "Show source journal entries contributing to a GL account balance")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Journal line drilldown successful", 
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = JournalLineDrilldownResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Invalid account ID or date range"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements")
    })
    public ResponseEntity<List<JournalLineDrilldownResponse>> drilldownToJournalLines(
            @Parameter(description = "GL Account ID (UUID)", required = true, example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable @NonNull String accountId,

            @Parameter(description = "Period start date (YYYY-MM-DD)", required = true, example = "2024-01-01") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @NonNull LocalDate startDate,

            @Parameter(description = "Period end date (YYYY-MM-DD)", required = true, example = "2024-12-31") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @NonNull LocalDate endDate) {

        // Use IllegalArgumentException for validation errors to leverage the module's
        // @RestControllerAdvice (APPaymentExceptionHandler) which maps it to 400 Bad Request
        // with a consistent ErrorResponse format (errorCode: "VALIDATION_ERROR").
        // This maintains the accounting domain's standard error contract across all endpoints.
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        List<JournalLineDrilldownResponse> response = financialReportingService.drilldownToJournalLines(
                accountId, startDate, endDate);
        return ResponseEntity.ok(response);
    }
}
