package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.InvoicingLagReport;
import com.positivity.invoice.internal.dto.RevenueByCustomerReport;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.InvoiceAnalyticsService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invoice analytics (Wave 2 capability, issues #1589 and #1592): revenue-by-customer and
 * workorder-creation-to-invoice lag reports.
 */
@RestController
@RequestMapping("/v1/invoices/analytics")
@Tag(name = "Invoice Analytics", description = "Revenue and lag reporting over invoices")
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"invoice:analytics:view"})
@RequiredArgsConstructor
public class InvoiceAnalyticsController {

    /** Default row cap when the caller omits {@code limit}. */
    static final int DEFAULT_LIMIT = 20;

    /** Hard cap so a caller cannot request an unbounded per-customer fan-out. */
    static final int MAX_LIMIT = 100;

    private final InvoiceAnalyticsService invoiceAnalyticsService;

    @Operation(operationId = "getRevenueByCustomer", summary = "Get Revenue By Customer", description = """
                    Returns per-customer revenue for invoices in [startDate, endDate], anchored on \
                    Invoice.createdAt — this invoice's own draft-creation timestamp, not finalizedAt, which \
                    can be null for a non-finalized invoice — ordered by revenue descending and bounded to \
                    `limit` rows — the top N by revenue, with no pagination to walk. \
                    Only revenue-recognized invoices count (status FINALIZED or POSTED); DRAFT invoices have not \
                    been billed and CANCELLED/ERROR invoices never will be. Deposit-take invoices — the document \
                    a deposit-take order renders for the down-payment itself — are excluded (#1623): a deposit is \
                    a contract liability, not a sale, and the later settlement invoice already carries the full \
                    gross amount, so counting both would overstate revenue by every deposit taken. \
                    Each row's avgInvoiceValue is \
                    revenue / invoiceCount, computed here rather than left to the caller, and lastInvoiceDate is \
                    that customer's most recent contributing invoice in the window.
                    Use this tool to find the highest-revenue customers in a period; the response's `truncated` \
                    flag tells you when more customers had revenue than `limit` allowed through, without a \
                    second call.
                    Preconditions: none; an empty window yields an empty `rows` list.
                    Required inputs: startDate and endDate (ISO dates), with endDate on or after startDate; \
                    limit is optional, defaults to 20, and is hard-capped at 100.
                    Emits an INVOICE_ANALYTICS_REVENUE_BY_CUSTOMER_VIEW audit event; no state changes — this is \
                    a read-only projection.
                    Returns 400 when the end date is before the start date.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description =
                        "Revenue-by-customer report returned (rows empty when no revenue-recognized invoice exists in the window).",
                content = @Content(schema = @Schema(implementation = RevenueByCustomerReport.class))),
        @ApiResponse(
                responseCode = "400",
                description = "endDate is before startDate.",
                content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Caller lacks the invoice:analytics:view authority.",
                content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/revenue-by-customer")
    @PreAuthorize("hasAuthority('" + InvoicePermissions.ANALYTICS_VIEW + "')")
    @EmitEvent(id = "INVOICE_ANALYTICS_REVENUE_BY_CUSTOMER_VIEW", apiVersion = "1")
    public RevenueByCustomerReport getRevenueByCustomer(
            @Parameter(
                            description = "Window start date (YYYY-MM-DD, inclusive)",
                            required = true,
                            example = "2026-06-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate startDate,
            @Parameter(description = "Window end date (YYYY-MM-DD, inclusive)", required = true, example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate endDate,
            @Parameter(
                            description = "Maximum rows to return, top-by-revenue (default 20, hard-capped at 100)",
                            example = "20")
                    @RequestParam(required = false)
                    Integer limit) {
        // Validated here too (InvoiceAnalyticsServiceImpl repeats it): this module's controllers
        // reject a bad date range before touching the repository (see
        // FinancialReportingController in pos-accounting, the structural template for this
        // controller), and a caller of the service interface directly still gets the guard.
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        return invoiceAnalyticsService.revenueByCustomer(startDate, endDate, clampLimit(limit));
    }

    @Operation(operationId = "getInvoicingLag", summary = "Get Workorder-Creation-To-Invoice Lag", description = """
                    Returns the average number of days from workorder creation to invoice creation for invoices \
                    created in [startDate, endDate], anchored on Invoice.createdAt — the invoice's own draft \
                    creation timestamp in this module, not finalizedAt — against the linked workorder's own \
                    creation timestamp replicated from pos-workorder.
                    Use this tool to track how quickly workorders turn into invoices; do not use \
                    getRevenueByCustomer for this, which reports per-customer revenue amounts, not throughput timing.
                    An invoice with no linked workorder, or whose workorder replica has no known creation \
                    timestamp yet, is excluded from both the average and the count returned alongside it — \
                    never treated as zero lag — and count rides with the average so a thin window reads as \
                    thin, not as a trend swing.
                    Preconditions: none; a window with no qualifying invoice yields one row with a null average \
                    and count 0.
                    Required inputs: startDate and endDate (ISO dates), with endDate on or after startDate; \
                    there is no limit parameter, since the response is a single aggregate row today, not a list \
                    to cap.
                    Emits an INVOICE_ANALYTICS_INVOICING_LAG_VIEW audit event; no state changes — this is a \
                    read-only projection.
                    Returns 400 when the end date is before the start date.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description =
                        "Invoicing-lag report returned (average is null and count is 0 when no invoice qualifies).",
                content = @Content(schema = @Schema(implementation = InvoicingLagReport.class))),
        @ApiResponse(
                responseCode = "400",
                description = "endDate is before startDate.",
                content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Caller lacks the invoice:analytics:view authority.",
                content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/invoicing-lag")
    @PreAuthorize("hasAuthority('" + InvoicePermissions.ANALYTICS_VIEW + "')")
    @EmitEvent(id = "INVOICE_ANALYTICS_INVOICING_LAG_VIEW", apiVersion = "1")
    public InvoicingLagReport getInvoicingLag(
            @Parameter(
                            description = "Window start date (YYYY-MM-DD, inclusive)",
                            required = true,
                            example = "2026-06-01")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate startDate,
            @Parameter(description = "Window end date (YYYY-MM-DD, inclusive)", required = true, example = "2026-06-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @NonNull
                    LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        return invoiceAnalyticsService.invoicingLag(startDate, endDate);
    }

    /**
     * Applies the documented default when {@code limit} is omitted, and hard-caps an
     * over-large request at {@link #MAX_LIMIT} (mirrors {@code InvoiceSearchController}'s page
     * size clamp). A non-positive {@code limit} is a client error, not silently substituted —
     * unlike an over-large one, it cannot be satisfied by returning less than asked.
     */
    private static int clampLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
