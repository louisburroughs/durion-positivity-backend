package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.CashPositionResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.CashPositionService;
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
 * Your cash (CAP:550 S19, #2515; SPEC-accounting-workspace §4.1, §7.1 "Cash position read"; AW9): in the bank, waiting
 * to be deposited and kept in drawers, with what needs attention.
 */
@RestController
@RequestMapping("/v1/accounting")
@RequiredArgsConstructor
@Tag(name = "Cash Position", description = "Your cash and where it sits, and the 30-day outlook")
public class CashPositionController {

    private final CashPositionService cashPositionService;

    @GetMapping(value = "/cash-position", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"reporting:view:financial-statements"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @Operation(
            operationId = "getCashPosition",
            summary = "Get Cash Position",
            description = """
                    Reads how much cash the shop has and where it sits: in the bank (the books balance of each \
                    active functional-currency bank account), waiting to be deposited (undeposited funds and \
                    register cash clearing, netted) and kept in drawers, with their total, plus what needs \
                    attention (settlement suspense, aged undeposited sessions, a clearing residual, aged unapplied \
                    vendor cash payments, a low point below the safety cushion) and the unpaid walk-in sales.
                    Use this tool to answer "how much cash do we have and where is it"; do not use the balance \
                    sheet for this, because it does not separate drawers or what waits to be deposited, and do \
                    not use getCashOutlook for what comes next.
                    Preconditions: the caller needs reporting:view:financial-statements authority; with \
                    accounting:reconciliation:view each bank row also carries the latest statement's closing \
                    balance, the reconciled-through date, open outstanding items and unmatched bank lines, shown \
                    beside the books balance and never added to the total.
                    Required inputs: none.
                    Emits an ACCOUNTING_CASH_POSITION_GET event and changes no state; foreign-currency bank \
                    accounts are left out and counted in excludedForeignCurrencyAccounts, and lagging is true when \
                    the newest fact behind the unpaid walk-in figure is older than the lag threshold.
                    Returns 200 with the position and 403 when the caller lacks the authority.
                    """,
            tags = {"Cash Position"})
    @ApiResponse(
            responseCode = "200",
            description = "The cash position with its components, needs-attention items and unpaid walk-in sales",
            content = @Content(schema = @Schema(implementation = CashPositionResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks reporting:view:financial-statements",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_CASH_POSITION_GET", apiVersion = "1")
    public ResponseEntity<CashPositionResponse> getCashPosition() {
        return ResponseEntity.ok(cashPositionService.read());
    }
}
