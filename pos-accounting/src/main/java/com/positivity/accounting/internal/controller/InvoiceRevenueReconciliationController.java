package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.InvoiceRevenueReconcileRequest;
import com.positivity.accounting.internal.dto.InvoiceRevenueReconcileResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.InvoiceRevenueReconciliationService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator entry point for invoice revenue reconciliation (#1851). */
@RestController
@RequestMapping("/v1/accounting/invoice-revenue")
@Validated
@Tag(name = "Accounting GL", description = "General ledger reconciliation")
public class InvoiceRevenueReconciliationController {

    private final InvoiceRevenueReconciliationService reconciliationService;

    public InvoiceRevenueReconciliationController(@NonNull InvoiceRevenueReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/reconcile")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:gl:reconcile"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.GL_RECONCILE + "')")
    @Operation(
            operationId = "reconcileInvoiceRevenue",
            summary = "Reconcile Invoice Revenue Postings",
            description = """
                    Posts revenue to the general ledger for every finalized invoice in the accounting replica \
                    that has no open revenue posting yet, using the same entries the live invoice event path \
                    creates (Dr Accounts Receivable / Cr Service Revenue / Cr Sales Tax Payable, dated at the \
                    invoice's finalization).
                    Use this tool to backfill invoices finalized before revenue posting existed, after a \
                    consumer outage, or when the income statement disagrees with invoice totals; do not use \
                    retryAccountingEvent, which re-runs a single failed ingestion event, or an outbox replay, \
                    which cannot reach invoices that never produced an event.
                    Preconditions: caller holds accounting:gl:reconcile. Idempotent: an invoice already on \
                    the ledger is reported as ALREADY_POSTED and left alone; a second run posts nothing new.
                    Required inputs: none. Optional body: finalizedFrom / finalizedTo (half-open instant \
                    window), invoiceIds (overrides the window), dryRun (report without posting), limit \
                    (oldest first).
                    Emits an ACCOUNTING_INVOICE_REVENUE_RECONCILE event and returns 200 with per-invoice \
                    outcomes (POSTED, WOULD_POST, ALREADY_POSTED, SKIPPED with reason, FAILED with error) \
                    and totals.
                    """,
            tags = {"Accounting GL"})
    @ApiResponse(responseCode = "200", description = "Reconciliation run completed; see per-invoice outcomes")
    @ApiResponse(responseCode = "400", description = "Invalid bounds (limit outside 1-5000)")
    @ApiResponse(responseCode = "403", description = "Caller lacks accounting:gl:reconcile")
    @EmitEvent(id = "ACCOUNTING_INVOICE_REVENUE_RECONCILE", apiVersion = "1")
    public ResponseEntity<InvoiceRevenueReconcileResponse> reconcile(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = false,
                            description = "Optional bounds; an empty or absent body reconciles everything, live.",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Dry run for July-August 2026", value = """
                                                                    {"finalizedFrom":"2026-07-01T00:00:00Z",
                                                                     "finalizedTo":"2026-09-01T00:00:00Z",
                                                                     "dryRun":true}
                                                                    """)))
                    @Valid
                    @RequestBody(required = false)
                    @Nullable
                    InvoiceRevenueReconcileRequest request) {
        InvoiceRevenueReconcileRequest effective =
                request == null ? InvoiceRevenueReconcileRequest.everything() : request;
        return ResponseEntity.ok(reconciliationService.reconcile(effective));
    }
}
