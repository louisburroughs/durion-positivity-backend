package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.InputTaxRecoveryResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.InputTaxRecoveryService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The input-tax recovery settings read (CAP:550 S32d item 2; SPEC-accounting-workspace §4.7): per registered regime
 * whether recovery is on and where it posts, the evidence rules, each petty-expense category's share, and the change
 * history.
 */
@RestController
@RequestMapping("/v1/accounting/input-tax-recovery")
@Tag(name = "Accounting Input-Tax Recovery", description = "Which indirect tax the shop paid may be claimed back")
public class InputTaxRecoveryController {

    private final InputTaxRecoveryService service;

    public InputTaxRecoveryController(@NonNull InputTaxRecoveryService service) {
        this.service = service;
    }

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:mapping-key:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.MAPPING_KEY_VIEW + "')")
    @Operation(
            operationId = "getInputTaxRecovery",
            summary = "Get Input-Tax Recovery Settings",
            description = """
                    Returns the tenant's input-tax recovery settings: one row per regime it holds a registration \
                    for (country, regime, whether recovery is on today, the registration in effect and the account \
                    recovered tax posts to), the evidence rules of each registered country, every petty-expense \
                    category's recoverable flag and share with its version, the change history (date, actor and \
                    role, old to new, reason) and asOf.
                    Use this tool to show what the shop can claim back and from which receipts; do not use \
                    listTaxRegistrations, which lists the registrations without recovery, accounts or shares.
                    Preconditions: caller holds accounting:mapping-key:view; the tenant's accounting time zone is \
                    set (422 ACCOUNTING_TIME_ZONE_UNSET otherwise). Read-only and idempotent.
                    Required inputs: none. A tenant without a registration, every USD tenant today, gets an empty \
                    regimes list.
                    The read never fails because of the tax service: evidenceRules is then null and enabled is null, \
                    meaning it cannot be determined now, never false. Emits an ACCOUNTING_INPUT_TAX_RECOVERY_VIEW \
                    event and returns 200.
                    """,
            tags = {"Accounting Input-Tax Recovery"})
    @ApiResponse(responseCode = "200", description = "The settings")
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:mapping-key:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_INPUT_TAX_RECOVERY_VIEW", apiVersion = "1")
    public ResponseEntity<InputTaxRecoveryResponse> read() {
        return ResponseEntity.ok(service.read());
    }
}
