package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.invoice.service.InvoiceFetchRunner;
import com.positivity.supplier.internal.invoice.service.InvoiceFetchScheduler;
import com.positivity.supplier.internal.security.SupplierPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Running a vendor invoice fetch on demand (CAP-321 #1343).
 *
 * <h2>Why an operator needs this</h2>
 *
 * The schedule covers the ordinary case. This exists for the moment somebody is looking for an
 * invoice a vendor insists it sent — and the alternative to an endpoint is editing a checkpoint by
 * hand, which is how a fortnight of invoices gets skipped in the course of finding one.
 *
 * <p>It runs the same code as the schedule and deliberately does not move the checkpoint: asking
 * about a particular window is investigating, not redefining where the schedule has reached.
 */
@RestController
@RequestMapping("/v1/supplier/invoices")
@RequiredArgsConstructor
@Tag(name = "Supplier Invoices", description = "On-demand vendor invoice retrieval")
public class SupplierInvoiceFetchController {

    private final InvoiceFetchScheduler invoiceFetchScheduler;

    @PostMapping("/{supplierRef}/fetches")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"supplier:invoice:fetch"})
    @PreAuthorize("hasAuthority('" + SupplierPermissions.INVOICE_FETCH + "')")
    @EmitEvent(id = "SUPPLIER_INVOICE_FETCH", apiVersion = "1")
    @Operation(
            operationId = "fetchSupplierInvoices",
            summary = "Fetch Vendor Invoices For A Window",
            description = """
                    Asks a vendor for the invoices it issued between two dates and imports the ones not already \
                    held.
                    Use this tool when somebody is chasing an invoice a vendor says it sent; do not use it to \
                    catch a schedule up, because it deliberately leaves the checkpoint alone and the scheduled \
                    run will still cover its own window.
                    Preconditions: the vendor profile must be enabled and have an invoice-fetch binding \
                    configured; fromDate must not be after toDate.
                    Required inputs: supplierRef path parameter, and fromDate and toDate query parameters as \
                    ISO dates.
                    Emits a SUPPLIER_INVOICE_FETCH event and, for each invoice not already held, publishes it \
                    for the accounting domain to record as a vendor bill.
                    Returns 200 with the number the vendor sent and the number newly imported, 422 when the \
                    vendor refused the window or could not be reached, 404 when the vendor profile is unknown, \
                    and 409 when the profile is disabled or has no invoice-fetch binding configured.
                    """,
            tags = {"Supplier Invoices"})
    @ApiResponse(responseCode = "200", description = "Window fetched")
    @ApiResponse(
            responseCode = "404",
            description = "Vendor profile unknown",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Vendor profile disabled, or no invoice-fetch binding configured",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The vendor refused the window or could not be reached",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Map<String, Object>> fetchSupplierInvoices(
            @Parameter(description = "Vendor profile alias", required = true) @PathVariable String supplierRef,
            @Parameter(description = "First issue date to fetch, inclusive", required = true)
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate fromDate,
            @Parameter(description = "Last issue date to fetch, inclusive", required = true)
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate toDate) {
        InvoiceFetchRunner.FetchOutcome outcome =
                invoiceFetchScheduler.fetchOnDemand(new SupplierRef(supplierRef), fromDate, toDate);
        // Both numbers, because they answer different questions. "fetched" says whether the vendor
        // had anything for this window at all; "imported" says how much of it was new. An operator
        // running this twice needs to see 12 and 0 and understand that as working, not as broken.
        return ResponseEntity.ok(Map.of(
                "supplierRef",
                supplierRef,
                "fromDate",
                fromDate.toString(),
                "toDate",
                toDate.toString(),
                "fetched",
                outcome.fetched(),
                "imported",
                outcome.imported()));
    }
}
