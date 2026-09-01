package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.CollectionsAnalyticsReport;
import com.positivity.accounting.internal.dto.PaymentLagCohortsReport;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.AccountingAnalyticsService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.jspecify.annotations.NonNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Wave 2 read-only accounting analytics (Issue #1590 E2, Issue #1591 E3).
 *
 * <p>Both endpoints are single-window aggregates over data pos-accounting already persists (the
 * {@code ExtInvoice} replica and this module's own {@code PaymentApplication} settlement records);
 * neither requires a schema change. Follows the {@link FinancialReportingController} annotation
 * style but is kept as its own controller, mirroring how {@link LaborOverheadReportController}
 * already sits alongside it as a separate single-purpose report family.
 */
@RestController
@RequestMapping("/v1/accounting/analytics")
@Tag(
        name = "Accounting Analytics",
        description = "Read-only cross-invoice collections and payment-lag analytics (Wave 2)")
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"accounting:analytics:view"})
@Validated
public class AccountingAnalyticsController {

    private final AccountingAnalyticsService accountingAnalyticsService;

    public AccountingAnalyticsController(AccountingAnalyticsService accountingAnalyticsService) {
        this.accountingAnalyticsService = accountingAnalyticsService;
    }

    /**
     * Invoiced-vs-collected analytics for one date window (Issue #1590, E2).
     */
    @GetMapping(value = "/collections", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.ANALYTICS_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_ANALYTICS_COLLECTIONS_VIEW", apiVersion = "1")
    @Operation(
            operationId = "getCollectionsAnalytics",
            summary = "Get Invoiced Vs Collected Analytics",
            description = """
                    Returns one aggregate row for a single date window: invoiced (sum of ExtInvoice totals \
                    for invoices finalized in the window), collected (sum of settled PaymentApplication \
                    amounts applied in the window) and a server-derived collectionRatePct.
                    IMPORTANT: invoiced and collected are deliberately DIFFERENT COHORTS of invoices — the \
                    invoice finalized in this window and the invoice whose payment settled in this window \
                    can be entirely different invoices. Do not present collectionRatePct as "what fraction \
                    of this window's invoices got paid"; it is a period-level cash-efficiency signal, not a \
                    cohort collection rate.
                    collected counts only settled cash (PaymentApplication.appliedAmount); it never counts \
                    pos-invoice's pre-settlement PaymentIntent/Receipt artifacts — an intent is not a \
                    collection.
                    BUDGET: this endpoint answers ONE window per call and returns a single row (no limit \
                    parameter — there is nothing to page). For a multi-period question (e.g. "weekly \
                    collections for the last 12 weeks"), do NOT loop this tool call-by-call; for more than \
                    3 periods that is out of budget. A future groupBy=month|week parameter will cover \
                    multi-period requests directly — until then, either ask the caller to narrow to at \
                    most 3 periods or wait for that parameter.
                    Preconditions: none; a window with no finalized invoices and no applications returns \
                    invoiced=0, collected=0 and collectionRatePct=null.
                    Required inputs: startDate and endDate (ISO dates), with endDate on or after startDate.
                    Emits an ACCOUNTING_ANALYTICS_COLLECTIONS_VIEW audit event; no state changes.
                    Returns 400 when the end date is before the start date.
                    collectionRatePct is null when invoiced is zero (the ratio is undefined) — never a \
                    divide-by-zero error and never a misleading 0.
                    """,
            tags = {"Accounting Analytics"})
    @ApiResponse(
            responseCode = "200",
            description = "Collections analytics generated successfully",
            content = @Content(schema = @Schema(implementation = CollectionsAnalyticsReport.class)))
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
            description = "Forbidden - missing accounting:analytics:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CollectionsAnalyticsReport> getCollectionsAnalytics(
            @Parameter(description = "Window start date (YYYY-MM-DD)", required = true, example = "2026-06-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate startDate,
            @Parameter(description = "Window end date (YYYY-MM-DD)", required = true, example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate endDate) {

        // Use IllegalArgumentException for validation errors to leverage the module's
        // @RestControllerAdvice (AccountingExceptionHandler) which maps it to 400 Bad Request
        // with a consistent ApiError format, matching every other reporting endpoint in this
        // module (ADR-0017).
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        CollectionsAnalyticsReport report = accountingAnalyticsService.getCollectionsAnalytics(startDate, endDate);
        return ResponseEntity.ok(report);
    }

    /**
     * Payment-lag cohorts for invoices issued in one date window (Issue #1591, E3).
     */
    @GetMapping(value = "/payment-lag-cohorts", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.ANALYTICS_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_ANALYTICS_PAYMENT_LAG_COHORTS_VIEW", apiVersion = "1")
    @Operation(
            operationId = "getPaymentLagCohorts",
            summary = "Get Payment Lag Cohorts",
            description = """
                    Generates payment-lag cohorts for invoices issued (finalized) in a date window: exactly \
                    four cohorts (<=30, 31-60, 61-90, unpaid) with invoice count and total invoice amount \
                    each.
                    issuedFrom/issuedTo anchor on invoice ISSUE date (ExtInvoice.finalizedAt), not payment \
                    date — the cohort bucket is a property of invoices issued in the window, whatever \
                    happened to them since. Lag is the whole days from that issue date to the \
                    PaymentApplication at which invoiceBalanceAfter first reaches zero; boundaries are \
                    inclusive at the upper edge (<=30 means 30 days or fewer; 31-60 includes exactly 60).
                    unpaid is a real cohort, not an omission: it holds invoices with no application, \
                    invoices only partially applied (invoiceBalanceAfter never reached zero in the observed \
                    data — these stay in unpaid until they are fully paid, however many periods that takes), \
                    and invoices whose full-payment lag exceeded 90 days (there is no separate 90+ bucket; a \
                    very slow full payment is grouped with never-paid because both missed the 90-day \
                    collection window). Every cohort's amount is the invoice's full total, never a partial \
                    or remaining balance.
                    Use this tool for AR collection-speed health; do not use getCollectionsAnalytics, which \
                    is a period cash total, not a per-invoice speed distribution. This is a single-window \
                    endpoint with no groupBy equivalent — the four cohorts already are the buckets.
                    Preconditions: none; a window with no finalized invoices returns all four cohorts at \
                    count 0 / amount 0.
                    Required inputs: issuedFrom and issuedTo (ISO dates), with issuedTo on or after \
                    issuedFrom; limit is optional (defaults to 4, i.e. all cohorts) and bounds how many of \
                    the fixed-order rows are returned, capped at 4 server-side — consistent with this \
                    module's other list-shaped analytics endpoints (e.g. searchVendors) even though the \
                    natural result is always at most 4 rows.
                    Emits an ACCOUNTING_ANALYTICS_PAYMENT_LAG_COHORTS_VIEW audit event; no state changes.
                    Returns 400 when issuedTo is before issuedFrom.
                    """,
            tags = {"Accounting Analytics"})
    @ApiResponse(
            responseCode = "200",
            description = "Payment-lag cohorts generated successfully",
            content = @Content(schema = @Schema(implementation = PaymentLagCohortsReport.class)))
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
            description = "Forbidden - missing accounting:analytics:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PaymentLagCohortsReport> getPaymentLagCohorts(
            @Parameter(description = "Issue-date window start (YYYY-MM-DD)", required = true, example = "2026-01-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate issuedFrom,
            @Parameter(description = "Issue-date window end (YYYY-MM-DD)", required = true, example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate issuedTo,
            @Parameter(
                            description = "Maximum cohort rows to return, from the fixed order (server caps at 4)",
                            example = "4")
                    @RequestParam(required = false, defaultValue = "4")
                    int limit) {

        // Use IllegalArgumentException for validation errors to leverage the module's
        // @RestControllerAdvice (AccountingExceptionHandler) which maps it to 400 Bad Request
        // with a consistent ApiError format, matching every other reporting endpoint in this
        // module (ADR-0017).
        if (issuedTo.isBefore(issuedFrom)) {
            throw new IllegalArgumentException("issuedTo cannot be before issuedFrom");
        }

        PaymentLagCohortsReport report = accountingAnalyticsService.getPaymentLagCohorts(issuedFrom, issuedTo, limit);
        return ResponseEntity.ok(report);
    }
}
