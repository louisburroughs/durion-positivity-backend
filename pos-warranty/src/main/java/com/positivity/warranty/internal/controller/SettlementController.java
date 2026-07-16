package com.positivity.warranty.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.warranty.internal.dto.SettlementCreateRequest;
import com.positivity.warranty.internal.dto.SettlementResponse;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement execution endpoint (PRD §3.6, §8): the clerk executes the chosen settlement(s) on
 * an approved claim — warranty creates the invoice adjustment/refund via pos-invoice or links
 * the replacement workorder, and the first success moves the claim to {@code SETTLED}. Thin
 * controller — all rules live in {@code SettlementService}.
 */
@Tag(name = "Warranty Settlements", description = "Execute settlements on approved warranty claims")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/warranty")
public class SettlementController {

    private final SettlementService settlementService;

    @Operation(
            summary = "Execute a settlement",
            description = "Executes one settlement on an APPROVED (or already SETTLED) claim: creates an invoice"
                    + " adjustment/refund via pos-invoice for credit/refund types, links a validated replacement"
                    + " workorder, or records NO_ACTION. The first successful settlement moves the claim to SETTLED.")
    @ApiResponse(responseCode = "201", description = "Settlement executed.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Claim is not in a settleable state.")
    @ApiResponse(responseCode = "422", description = "Referenced replacement workorder could not be resolved.")
    @ApiResponse(responseCode = "502", description = "pos-invoice write failed; settlement recorded as FAILED.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_SETTLE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_SETTLE", apiVersion = "1")
    @PostMapping("/claims/{id}/settlements")
    public ResponseEntity<SettlementResponse> createSettlement(
            @Parameter(description = "Claim id") @PathVariable("id") UUID claimId,
            @Valid @NotNull @RequestBody SettlementCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(settlementService.create(claimId, request));
    }
}
