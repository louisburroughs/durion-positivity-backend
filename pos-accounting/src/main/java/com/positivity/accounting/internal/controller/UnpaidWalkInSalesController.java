package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.UnpaidWalkInSalesResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.UnpaidWalkInSalesService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unpaid walk-in sales (CAP:550 S11, #2508; SPEC-accounting-workspace §4.1 "Unpaid walk-in sales",
 * §4.4 item 2, §9.5a; AW12): the CASH house account's balance and the day-end needs-attention item.
 */
@RestController
@RequestMapping("/v1/accounting")
@RequiredArgsConstructor
@Tag(name = "Financial Reporting", description = "Income Statement and Balance Sheet generation with drilldown")
public class UnpaidWalkInSalesController {

    private final UnpaidWalkInSalesService unpaidWalkInSalesService;

    @GetMapping(value = "/unpaid-walk-in-sales", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"reporting:view:financial-statements"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @Operation(
            operationId = "getUnpaidWalkInSales",
            summary = "Get Unpaid Walk-in Sales",
            description = """
                    Reads what is still owed on the CASH walk-in house account: the balance, its open invoices \
                    oldest sale first, the invoices whose business day has ended at their location \
                    (needsAttention), and walk-in payments with money left unapplied.
                    Use this tool to check that the CASH receivable nets to zero each day and to find walk-in \
                    sales to collect or credit; do not use aged receivables for this, because it leaves the \
                    CASH account out.
                    Preconditions: the caller needs reporting:view:financial-statements authority; a business \
                    day ends at local midnight in the location's time zone, or in UTC when the location has \
                    none (timezoneFallback).
                    Required inputs: none.
                    Emits an ACCOUNTING_UNPAID_WALK_IN_SALES_VIEW event and changes no state; each open \
                    invoice offers COLLECT and CREDIT_MEMO, while reassignment awaits a decision.
                    Returns 200 with houseAccountKnown false and zero amounts when accounting has not yet \
                    received the CASH account, and 403 when the caller lacks the authority.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "The CASH balance, open walk-in invoices, the needs-attention item and unapplied walk-in"
                    + " payments")
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks reporting:view:financial-statements",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_UNPAID_WALK_IN_SALES_VIEW", apiVersion = "1")
    public ResponseEntity<UnpaidWalkInSalesResponse> getUnpaidWalkInSales() {
        return ResponseEntity.ok(unpaidWalkInSalesService.read());
    }
}
