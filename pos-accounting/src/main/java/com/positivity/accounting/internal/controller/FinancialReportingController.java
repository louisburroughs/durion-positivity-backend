package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.AccountDrilldownResponse;
import com.positivity.accounting.internal.dto.AgedPayablesReport;
import com.positivity.accounting.internal.dto.AgedReceivablesReport;
import com.positivity.accounting.internal.dto.BalanceSheetReport;
import com.positivity.accounting.internal.dto.GeneralLedgerReport;
import com.positivity.accounting.internal.dto.IncomeStatementReport;
import com.positivity.accounting.internal.dto.JournalLineDrilldownResponse;
import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.dto.TrialBalanceReport;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.FinancialReportingService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
@RequestMapping("/v1/accounting/reports/financial")
@Tag(name = "Financial Reporting", description = "Income Statement and Balance Sheet generation with drilldown")
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"reporting:view:financial-statements"})
@Validated
public class FinancialReportingController {

    private final FinancialReportingService financialReportingService;

    public FinancialReportingController(FinancialReportingService financialReportingService) {
        this.financialReportingService = financialReportingService;
    }

    /**
     * Generate Income Statement (Profit & Loss) for a date range.
     */
    @GetMapping(value = "/income-statement", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "REPORT_INCOME_STATEMENT_GENERATE", apiVersion = "1")
    @Operation(
            operationId = "generateIncomeStatement",
            summary = "Generate Income Statement",
            description = """
                    Generates the Profit and Loss statement for a date range, aggregating POSTED journal \
                    activity into revenue, expense and net-income lines.
                    Use this tool for period profitability; do not use generateBalanceSheet, which is the \
                    point-in-time financial position, and use drilldownToAccounts to expand a statement line.
                    Preconditions: none; a period with no POSTED activity yields zeroed lines.
                    Required inputs: startDate and endDate (ISO dates) as query parameters, with endDate on \
                    or after startDate.
                    Emits a REPORT_INCOME_STATEMENT_GENERATE audit event; no state changes.
                    Returns 400 when the end date is before the start date.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "Income statement generated successfully",
            content = @Content(schema = @Schema(implementation = IncomeStatementReport.class)))
    @ApiResponse(responseCode = "400", description = "Invalid date range")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements")
    public ResponseEntity<IncomeStatementReport> generateIncomeStatement(
            @Parameter(description = "Period start date (YYYY-MM-DD)", required = true, example = "2024-01-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate startDate,
            @Parameter(description = "Period end date (YYYY-MM-DD)", required = true, example = "2024-12-31")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate endDate) {

        // Use IllegalArgumentException for validation errors to leverage the module's
        // @RestControllerAdvice (APPaymentExceptionHandler) which maps it to 400 Bad
        // Request
        // with a consistent ApiError format (code: "VALIDATION_ERROR").
        // This maintains the accounting domain's standard error contract across all
        // endpoints.
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "REPORT_BALANCE_SHEET_GENERATE", apiVersion = "1")
    @Operation(
            operationId = "generateBalanceSheet",
            summary = "Generate Balance Sheet",
            description = """
                    Generates the Balance Sheet as of a specific date, presenting assets, liabilities and \
                    equity from POSTED journal activity.
                    Use this tool for financial position at a point in time; do not use \
                    generateIncomeStatement, which covers activity over a period, and use \
                    generateTrialBalance for the raw per-account debit and credit proof.
                    Preconditions: none; a date with no POSTED activity yields zeroed sections.
                    Required inputs: asOfDate (ISO date) as a query parameter.
                    Emits a REPORT_BALANCE_SHEET_GENERATE audit event; no state changes.
                    Returns 400 when the asOfDate is missing or malformed.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "Balance sheet generated successfully",
            content = @Content(schema = @Schema(implementation = BalanceSheetReport.class)))
    @ApiResponse(responseCode = "400", description = "Invalid date")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements")
    public ResponseEntity<BalanceSheetReport> generateBalanceSheet(
            @Parameter(description = "As-of date (YYYY-MM-DD)", required = true, example = "2024-12-31")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate asOfDate) {

        BalanceSheetReport report = financialReportingService.generateBalanceSheet(asOfDate);
        return ResponseEntity.ok(report);
    }

    /**
     * Generate Trial Balance as of a specific date.
     */
    @GetMapping(value = "/trial-balance", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "REPORT_TRIAL_BALANCE_GENERATE", apiVersion = "1")
    @Operation(
            operationId = "generateTrialBalance",
            summary = "Generate Trial Balance",
            description = """
                    Generates the Trial Balance as of a specific date: per-account debit, credit and balance \
                    rows from POSTED journal lines, grand totals proving total debits equal total credits, \
                    and an entry-number gap-check footnote per monthly sequence scope.
                    Use this tool to prove ledger integrity before period close; do not use \
                    generateBalanceSheet, which classifies balances into statement sections.
                    Preconditions: none; rows are empty when no POSTED data exists as of the date.
                    Required inputs: asOf (ISO date) as a query parameter.
                    Emits a REPORT_TRIAL_BALANCE_GENERATE audit event; no state changes.
                    Returns 400 when the asOf date is missing or malformed.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "Trial balance generated successfully (rows empty when no POSTED data exists as of the date)",
            content = @Content(schema = @Schema(implementation = TrialBalanceReport.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid or missing asOf date",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Unauthorized",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing reporting:view:financial-statements",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TrialBalanceReport> generateTrialBalance(
            @Parameter(description = "As-of date (YYYY-MM-DD)", required = true, example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate asOf) {

        // Malformed/missing asOf is rejected by Spring binding before reaching here;
        // the module's @RestControllerAdvice maps it to 400 Bad Request with the
        // accounting domain's standard ApiError format (ADR-0017).
        TrialBalanceReport report = financialReportingService.generateTrialBalance(asOf);
        return ResponseEntity.ok(report);
    }

    /**
     * Drill down from statement line to contributing GL accounts.
     */
    @GetMapping(value = "/drilldown/accounts/{statementLineCode}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "REPORT_DRILLDOWN_ACCOUNTS", apiVersion = "1")
    @Operation(
            operationId = "drilldownToAccounts",
            summary = "Drilldown To Accounts",
            description = """
                    Shows which GL accounts contribute to a specific financial-statement line over a date \
                    range, with each account's contribution amount.
                    Use this tool to expand one line of an income statement or balance sheet; do not use \
                    drilldownToJournalLines, which is the next level down from an account to its source \
                    entries.
                    Preconditions: the statementLineCode must follow the uppercase-and-underscores format \
                    (e.g. REVENUE_SALES).
                    Required inputs: statementLineCode as a path parameter plus startDate and endDate (ISO \
                    dates), with endDate on or after startDate.
                    Emits a REPORT_DRILLDOWN_ACCOUNTS audit event; no state changes.
                    Returns 400 when the code format is invalid or the end date precedes the start date.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "Account drilldown successful",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = AccountDrilldownResponse.class))))
    @ApiResponse(responseCode = "400", description = "Invalid statement line code or date range")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements")
    public ResponseEntity<List<AccountDrilldownResponse>> drilldownToAccounts(
            @Parameter(description = "Statement line code (e.g., REVENUE_SALES)", required = true)
                    @PathVariable
                    @NotBlank
                    @Pattern(
                            regexp = "^[A-Z_]{1,100}$",
                            message =
                                    "Invalid statement line code format: must contain only uppercase letters and underscores (1-100 characters)")
                    @NonNull
                    String statementLineCode,
            @Parameter(description = "Period start date (YYYY-MM-DD)", required = true, example = "2024-01-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate startDate,
            @Parameter(description = "Period end date (YYYY-MM-DD)", required = true, example = "2024-12-31")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate endDate) {

        // Use IllegalArgumentException for validation errors to leverage the module's
        // @RestControllerAdvice (APPaymentExceptionHandler) which maps it to 400 Bad
        // Request
        // with a consistent ApiError format (code: "VALIDATION_ERROR").
        // This maintains the accounting domain's standard error contract across all
        // endpoints.

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        List<AccountDrilldownResponse> response =
                financialReportingService.drilldownToAccounts(statementLineCode, startDate, endDate);
        return ResponseEntity.ok(response);
    }

    /**
     * Drill down from GL account to source journal lines.
     */
    @GetMapping(value = "/drilldown/journal-lines/{accountId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "REPORT_DRILLDOWN_JOURNAL_LINES", apiVersion = "1")
    @Operation(
            operationId = "drilldownToJournalLines",
            summary = "Drilldown To Journal Lines",
            description = """
                    Shows the source journal lines contributing to a GL account's balance over a date range, \
                    completing the statement-to-transaction audit path.
                    Use this tool after drilldownToAccounts has identified the account of interest; use \
                    getJournalEntry instead to open a full entry from a returned line.
                    Preconditions: the accountId path parameter must be a UUID.
                    Required inputs: accountId (UUID) as a path parameter plus startDate and endDate (ISO \
                    dates), with endDate on or after startDate.
                    Emits a REPORT_DRILLDOWN_JOURNAL_LINES audit event; no state changes.
                    Returns 400 when the account id is not a UUID or the end date precedes the start date.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "Journal line drilldown successful",
            content =
                    @Content(
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = JournalLineDrilldownResponse.class))))
    @ApiResponse(responseCode = "400", description = "Invalid account ID or date range")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements")
    public ResponseEntity<List<JournalLineDrilldownResponse>> drilldownToJournalLines(
            @Parameter(
                            description = "GL Account ID (UUID)",
                            required = true,
                            example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    @NotBlank
                    @Pattern(
                            regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                            message = "Account ID must be a valid UUID")
                    @NonNull
                    String accountId,
            @Parameter(description = "Period start date (YYYY-MM-DD)", required = true, example = "2024-01-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate startDate,
            @Parameter(description = "Period end date (YYYY-MM-DD)", required = true, example = "2024-12-31")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate endDate) {

        // Use IllegalArgumentException for validation errors to leverage the module's
        // @RestControllerAdvice (APPaymentExceptionHandler) which maps it to 400 Bad
        // Request
        // with a consistent ApiError format (code: "VALIDATION_ERROR").
        // This maintains the accounting domain's standard error contract across all
        // endpoints.

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        List<JournalLineDrilldownResponse> response =
                financialReportingService.drilldownToJournalLines(accountId, startDate, endDate);
        return ResponseEntity.ok(response);
    }

    /**
     * Generate a General Ledger report for a date range, optionally filtered to a
     * single account (Story G2, Issue #960).
     */
    @GetMapping(value = "/general-ledger", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "REPORT_GENERAL_LEDGER_GENERATE", apiVersion = "1")
    @Operation(
            operationId = "generateGeneralLedger",
            summary = "Generate General Ledger",
            description = """
                    Generates the General Ledger report for a date range: per-account chronological POSTED \
                    journal lines with a running balance, opening and closing balances, and grand totals.
                    Use this tool for full ledger detail over a period; do not use generateTrialBalance, \
                    which shows only closing balances per account, and use drilldownToJournalLines for a \
                    single account slice.
                    Preconditions: none; sections are empty when no POSTED activity exists.
                    Required inputs: startDate and endDate (ISO dates), with endDate on or after startDate; \
                    accountId (UUID) is optional and restricts the report to one account.
                    Emits a REPORT_GENERAL_LEDGER_GENERATE audit event; no state changes.
                    Returns 400 when the account id is not a UUID or the end date precedes the start date.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "General ledger generated successfully (sections empty when no POSTED activity exists)",
            content = @Content(schema = @Schema(implementation = GeneralLedgerReport.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid account ID or date range",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Unauthorized",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing reporting:view:financial-statements",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<GeneralLedgerReport> generateGeneralLedger(
            @Parameter(
                            description = "Optional GL account ID (UUID) to filter to a single account",
                            example = "123e4567-e89b-12d3-a456-426614174000")
                    @RequestParam(required = false)
                    @Pattern(
                            regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                            message = "Account ID must be a valid UUID")
                    @Nullable
                    String accountId,
            @Parameter(description = "Period start date (YYYY-MM-DD)", required = true, example = "2026-01-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate startDate,
            @Parameter(description = "Period end date (YYYY-MM-DD)", required = true, example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate endDate) {

        // Use IllegalArgumentException for validation errors to leverage the module's
        // @RestControllerAdvice (APPaymentExceptionHandler) which maps it to 400 Bad
        // Request with the accounting domain's standard ApiError format (ADR-0017).
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        GeneralLedgerReport report = financialReportingService.generateGeneralLedger(accountId, startDate, endDate);
        return ResponseEntity.ok(report);
    }

    /**
     * Generate the Aged Receivables report as of a date (Story G2, Issue #960).
     */
    @GetMapping(value = "/aged-receivables", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "REPORT_AGED_RECEIVABLES_GENERATE", apiVersion = "1")
    @Operation(
            operationId = "generateAgedReceivables",
            summary = "Generate Aged Receivables",
            description = """
                    Generates the Aged Receivables report as of a date: per-customer open invoice balances \
                    bucketed by days past due (0-30, 31-60, 61-90, 90+) with grand totals.
                    Use this tool to review customer collection exposure; do not use generateAgedPayables, \
                    which is the vendor-side mirror of this report.
                    Preconditions: none; rows are empty when no open receivables exist.
                    Required inputs: asOfDate (ISO date) as a query parameter.
                    Emits a REPORT_AGED_RECEIVABLES_GENERATE audit event; no state changes.
                    Returns 400 when the asOfDate is missing or malformed.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "Aged receivables generated successfully (rows empty when no open receivables exist)",
            content = @Content(schema = @Schema(implementation = AgedReceivablesReport.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid or missing asOfDate",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Unauthorized",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing reporting:view:financial-statements",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AgedReceivablesReport> generateAgedReceivables(
            @Parameter(description = "As-of date (YYYY-MM-DD)", required = true, example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate asOfDate) {

        // Malformed/missing asOfDate is rejected by Spring binding before reaching
        // here; the module's @RestControllerAdvice maps it to 400 Bad Request with the
        // accounting domain's standard ApiError format (ADR-0017).
        AgedReceivablesReport report = financialReportingService.generateAgedReceivables(asOfDate);
        return ResponseEntity.ok(report);
    }

    /**
     * Generate the Aged Payables report as of a date (Story G2, Issue #960).
     */
    @GetMapping(value = "/aged-payables", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "REPORT_AGED_PAYABLES_GENERATE", apiVersion = "1")
    @Operation(
            operationId = "generateAgedPayables",
            summary = "Generate Aged Payables",
            description = """
                    Generates the Aged Payables report as of a date: per-vendor open vendor-bill balances \
                    bucketed by days past due (0-30, 31-60, 61-90, 90+) with grand totals.
                    Use this tool to review what is owed to vendors and how overdue it is; do not use \
                    generateAgedReceivables, which is the customer-side mirror, and use listApBills to pick \
                    individual bills for payment.
                    Preconditions: none; rows are empty when no open payables exist.
                    Required inputs: asOfDate (ISO date) as a query parameter.
                    Emits a REPORT_AGED_PAYABLES_GENERATE audit event; no state changes.
                    Returns 400 when the asOfDate is missing or malformed.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "Aged payables generated successfully (rows empty when no open payables exist)",
            content = @Content(schema = @Schema(implementation = AgedPayablesReport.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid or missing asOfDate",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Unauthorized",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing reporting:view:financial-statements",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AgedPayablesReport> generateAgedPayables(
            @Parameter(description = "As-of date (YYYY-MM-DD)", required = true, example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate asOfDate) {

        // Malformed/missing asOfDate is rejected by Spring binding before reaching
        // here; the module's @RestControllerAdvice maps it to 400 Bad Request with the
        // accounting domain's standard ApiError format (ADR-0017).
        AgedPayablesReport report = financialReportingService.generateAgedPayables(asOfDate);
        return ResponseEntity.ok(report);
    }

    /**
     * Generate the Sales-Tax Liability report for a date range (Story T8, Issue
     * #966).
     */
    @GetMapping(value = "/tax-liability", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "REPORT_TAX_LIABILITY_GENERATE", apiVersion = "1")
    @Operation(
            operationId = "generateTaxLiabilityReport",
            summary = "Generate Sales-Tax Liability Report",
            description = """
                    Generates the reconciliation-grade sales-tax liability report for a date range: \
                    per-jurisdiction (state, county, city, special) taxable base, exempt base with reasons, \
                    gross tax collected, POSTED credit-memo reversals netted pro-rata, and net tax, plus a \
                    GL-drift column reconciling total net tax against Sales-Tax Payable (2200) activity.
                    Use this tool for filing-period review on the accrual basis, where invoice tax buckets by \
                    finalization period and credits by posting period; do not use createTaxLiabilitySnapshot, \
                    which freezes a closed period's figures for filing.
                    Preconditions: none; rows are empty when no in-period tax exists.
                    Required inputs: startDate and endDate (ISO dates), with endDate on or after startDate.
                    Emits a REPORT_TAX_LIABILITY_GENERATE audit event; no state changes.
                    Returns 400 when the end date is before the start date.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "Tax liability report generated successfully (rows empty when no in-period tax exists)",
            content = @Content(schema = @Schema(implementation = TaxLiabilityReport.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid date range",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Unauthorized",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing reporting:view:financial-statements",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TaxLiabilityReport> generateTaxLiability(
            @Parameter(description = "Period start date (YYYY-MM-DD)", required = true, example = "2026-06-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate startDate,
            @Parameter(description = "Period end date (YYYY-MM-DD)", required = true, example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate endDate) {

        // Use IllegalArgumentException for validation errors to leverage the module's
        // @RestControllerAdvice (APPaymentExceptionHandler) which maps it to 400 Bad
        // Request with the accounting domain's standard ApiError format (ADR-0017).
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        TaxLiabilityReport report = financialReportingService.generateTaxLiability(startDate, endDate);
        return ResponseEntity.ok(report);
    }
}
