package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.CashOutlookResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.CashOutlookService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cash outlook (CAP:550 S19, #2515; SPEC-accounting-workspace §4.2, §7.1 "Cash outlook"; AW11): a projection of
 * cash over the coming days from approved bills and open invoices.
 */
@RestController
@RequestMapping("/v1/accounting")
@RequiredArgsConstructor
@Tag(name = "Cash Position", description = "Your cash and where it sits, and the 30-day outlook")
public class CashOutlookController {

    private final CashOutlookService cashOutlookService;

    @GetMapping(value = "/cash-outlook", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"reporting:view:financial-statements"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @Operation(
            operationId = "getCashOutlook",
            summary = "Get Cash Outlook",
            description = """
                    Projects the shop's cash day by day from what is in the bank and waiting to be deposited, \
                    adding open customer invoices on their due dates and subtracting approved vendor bills on \
                    their due dates, with the lowest point, the bills lane (overdue, due in 7 days, due later, \
                    due date estimated) and the safety cushion check. Bills without a due date use an estimate \
                    from their terms and are flagged; pending bills, overdue invoices and vendor credits are \
                    shown apart and left out of the line.
                    Use this tool to answer "will we have enough cash over the next weeks"; do not use \
                    getCashPosition for the cash held now, and do not use aged payables for what is due when.
                    Preconditions: the caller needs reporting:view:financial-statements authority; the \
                    projection starts from the bank and what waits to be deposited, never drawer cash or the \
                    float, and nothing is posted or stored.
                    Required inputs: days, optional, from 1 to 90 and 30 when omitted.
                    Emits an ACCOUNTING_CASH_OUTLOOK_GET event and changes no state; no bill's due date is ever \
                    written.
                    Returns 200 with the outlook, 400 VALIDATION_ERROR when days is outside 1 to 90 and 403 when \
                    the caller lacks the authority.
                    """,
            tags = {"Cash Position"})
    @ApiResponse(
            responseCode = "200",
            description = "The projected daily balances, low point, bills lane and cushion check",
            content = @Content(schema = @Schema(implementation = CashOutlookResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "days is outside 1 to 90 (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks reporting:view:financial-statements",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_CASH_OUTLOOK_GET", apiVersion = "1")
    public ResponseEntity<CashOutlookResponse> getCashOutlook(
            @Parameter(description = "Days to project after today, from 1 to 90", example = "30")
                    @RequestParam(name = "days", defaultValue = "30")
                    int days) {
        return ResponseEntity.ok(cashOutlookService.outlook(days));
    }
}
