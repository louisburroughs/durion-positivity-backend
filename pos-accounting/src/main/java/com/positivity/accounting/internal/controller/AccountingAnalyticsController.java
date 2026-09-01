package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.CollectionsAnalyticsReport;
import com.positivity.accounting.internal.dto.PaymentLagCohortsReport;
import com.positivity.accounting.internal.dto.VendorSpendReport;
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
 * REST API for Wave 2 read-only accounting analytics (Issue #1590 E2, Issue #1591 E3, Issue #1596
 * E8).
 *
 * <p>All three endpoints are single-window aggregates over data pos-accounting already persists
 * (the {@code ExtInvoice} replica and this module's own {@code PaymentApplication}/{@code
 * APPayment}/{@code VendorBill} records); none requires a schema change. Follows the {@link
 * FinancialReportingController} annotation style but is kept as its own controller, mirroring how
 * {@link LaborOverheadReportController} already sits alongside it as a separate single-purpose
 * report family.
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
                    Returns one aggregate row for a single date window: invoiced (the sum of ExtInvoice \
                    totals for invoices finalized in the window) and collected (the sum of settled \
                    PaymentApplication amounts applied in the window, never pos-invoice's pre-settlement \
                    PaymentIntent/Receipt artifacts), plus a server-derived collectionRatePct.
                    Use this tool for a single window's cash-collection efficiency; do not loop it across \
                    more than 3 periods for a multi-period trend such as 12 weekly windows, since that \
                    exceeds this endpoint's call budget — narrow the request instead, or wait for a future \
                    groupBy=month|week parameter.
                    Preconditions: none; a window with no finalized invoices and no applications returns \
                    invoiced=0, collected=0 and a null collectionRatePct, which is also returned whenever \
                    invoiced is zero rather than a misleading 0 or a divide-by-zero error.
                    Required inputs: startDate and endDate (ISO dates), with endDate on or after startDate; \
                    invoiced and collected are deliberately different invoice cohorts, since a payment can \
                    settle in a later window than the invoice it pays, so collectionRatePct is a period \
                    cash-efficiency signal rather than a cohort collection rate.
                    There is no limit parameter and no truncated flag, since the response is always exactly \
                    one aggregate row, not a list to cap.
                    Emits an ACCOUNTING_ANALYTICS_COLLECTIONS_VIEW audit event; no other state changes.
                    Returns 400 when endDate is before startDate.
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
                    four cohorts (<=30, 31-60, 61-90, unpaid) each with an invoice count and total invoice \
                    amount, where issuedFrom/issuedTo anchor on invoice issue date (ExtInvoice.finalizedAt) \
                    rather than payment date.
                    Use this tool for AR collection-speed distribution across invoices; do not use \
                    getCollectionsAnalytics instead, which reports a period cash total rather than a \
                    per-invoice speed distribution.
                    Preconditions: none; a window with no finalized invoices returns all four cohorts at \
                    count 0 and amount 0.
                    Required inputs: issuedFrom and issuedTo (ISO dates), with issuedTo on or after \
                    issuedFrom; limit is optional, defaults to 4, and is capped at 4 since the result is \
                    always at most four rows.
                    Lag is measured in whole days from the issue-date anchor to the PaymentApplication at \
                    which invoiceBalanceAfter first reaches zero, with boundaries inclusive at the upper \
                    edge; unpaid absorbs invoices with no application, invoices only partially applied, and \
                    invoices whose full-payment lag exceeded 90 days, each counted at its full invoice total \
                    rather than a remaining balance.
                    The response's truncated flag is true when limit dropped one or more of the four fixed \
                    cohorts, false when all four are present.
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

    /**
     * Per-vendor spend analytics for one date window (Issue #1596, E8).
     */
    @GetMapping(value = "/vendor-spend", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.ANALYTICS_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_ANALYTICS_VENDOR_SPEND_VIEW", apiVersion = "1")
    @Operation(
            operationId = "getVendorSpend",
            summary = "Get Vendor Spend Analytics",
            description = """
                    Returns per-vendor spend rows for a single date window, ordered by paidAmount descending \
                    and capped at limit, so the top N vendors by spend is simply the first rows with no \
                    paging needed.
                    Use this tool to find which vendors received the most A/P cash, or billed the most, in a \
                    period; do not use listApBills for this, which lists individual eligible bills rather \
                    than a per-vendor aggregate.
                    Preconditions: none; a window with no settled payments and no bills yields an empty rows \
                    list.
                    Required inputs: startDate and endDate (ISO dates), with endDate on or after startDate; \
                    limit is optional, defaults to 20, and is hard-capped at 100.
                    IMPORTANT: paidAmount (settled A/P cash — APPayment.grossAmount for payments whose \
                    paymentDate falls in the window and whose gateway status shows the cash already moved) \
                    and billCount/avgBillAmount (VendorBill records whose billDate falls in the window) are \
                    DIFFERENT POPULATIONS of the same vendor — a payment can settle bills billed in an \
                    earlier or later window, so avgBillAmount * billCount does not reconcile to paidAmount. \
                    avgBillAmount is 0, never null, when billCount is 0.
                    Emits an ACCOUNTING_ANALYTICS_VENDOR_SPEND_VIEW audit event; no state changes.
                    Returns 400 when endDate is before startDate, or limit is not a positive integer.
                    """,
            tags = {"Accounting Analytics"})
    @ApiResponse(
            responseCode = "200",
            description = "Vendor spend analytics generated successfully",
            content = @Content(schema = @Schema(implementation = VendorSpendReport.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid date range or limit",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Unauthorized",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing accounting:analytics:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorSpendReport> getVendorSpend(
            @Parameter(description = "Window start date (YYYY-MM-DD)", required = true, example = "2026-06-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate startDate,
            @Parameter(description = "Window end date (YYYY-MM-DD)", required = true, example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate endDate,
            @Parameter(
                            description = "Maximum vendor rows to return, top-by-paidAmount (default 20,"
                                    + " hard-capped at 100)",
                            example = "20")
                    @RequestParam(required = false, defaultValue = "20")
                    int limit) {

        // Use IllegalArgumentException for validation errors to leverage the module's
        // @RestControllerAdvice (AccountingExceptionHandler) which maps it to 400 Bad Request
        // with a consistent ApiError format, matching every other reporting endpoint in this
        // module (ADR-0017).
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        VendorSpendReport report = accountingAnalyticsService.getVendorSpend(startDate, endDate, limit);
        return ResponseEntity.ok(report);
    }
}
