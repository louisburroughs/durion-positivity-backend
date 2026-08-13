package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.dto.valuation.ValuationReportResponse;
import com.positivity.inventory.service.ValuationService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inventory valuation read endpoint (odoo-parity J2, issue #1052).
 *
 * <p>Reports on-hand value (on-hand × current unit cost) at SKU level with a total, optionally
 * site-scoped and/or reconstructed as of a historical instant. Quantities × cost are doubly
 * sensitive (DECISION-INVENTORY-011), so the dedicated {@code inventory:valuation:view} permission
 * guards these reads — value is never exposed under {@code inventory:on_hand:view} alone. The as-of
 * variant additionally requires {@code inventory:ledger:view} (history exposure, enforced by the
 * shared as-of guard) and rejects future instants with 422.
 *
 * <p><strong>No {@code @EmitEvent}.</strong> Following the pure-read precedent of
 * {@link ShortageController#listShortageOptions} (a computed read that emits no event), these
 * valuation reads carry no {@code @EmitEvent} and register no event types — the permission gate is
 * the audit boundary.
 */
@RestController
@RequestMapping("/v1/inventory/valuation")
@RequiredArgsConstructor
@Tag(name = "Inventory Valuation", description = "On-hand valuation read endpoints (odoo-parity J2)")
public class ValuationController {

    private final ValuationService valuationService;

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:valuation:view"})
    @PreAuthorize("hasAuthority('inventory:valuation:view')")
    @Operation(
            operationId = "getInventoryValuation",
            summary = "Get inventory valuation",
            description = """
                    Returns on-hand inventory value per SKU with a total, computing on-hand times the current unit \
                    cost derived from each SKU's resolved costing method — the running average under AVERAGE, the \
                    standard price (falling back to the latest-receipt memo) under STANDARD — with uncosted SKUs \
                    reported at null unit cost and zero value rather than as errors.
                    Use this tool for valuation reporting; use listInventoryLedger instead for the raw movements \
                    behind the numbers, and createRevaluation to change a cost rather than read it.
                    Preconditions: none for a current valuation; an asOf request additionally requires the \
                    inventory:ledger:view authority because it replays ledger history.
                    Required inputs: none are mandatory — locationId scopes to one site, sku restricts to one \
                    stock item, and asOf (ISO-8601 instant) reconstructs both on-hand and unit cost from the \
                    ledger as of that instant.
                    No events are emitted and no state changes; this is a read-only projection computed on demand \
                    and never persisted.
                    Returns 422 when asOf is in the future (AS_OF_IN_FUTURE) or when an uncapped full-catalog \
                    asOf request exceeds the configured SKU safety cap (default 1000), and 403 when asOf is \
                    requested without inventory:ledger:view.
                    """,
            tags = {"Inventory Valuation"})
    @ApiResponse(
            responseCode = "200",
            description = "Valuation report returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ValuationReportResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request parameters",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required valuation view authority (or ledger view for as-of)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "As-of instant is in the future",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ValuationReportResponse> getValuation(
            @Parameter(description = "Restrict valuation to one site") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Restrict valuation to one SKU / stock-item identifier")
                    @RequestParam(required = false)
                    String sku,
            @Parameter(
                            description = "Optional historical instant (ISO-8601). Reconstructs on-hand and unit cost"
                                    + " from the ledger as of this instant. Requires 'inventory:ledger:view'; future"
                                    + " instants are rejected (422).")
                    @RequestParam(required = false)
                    Instant asOf) {
        if (asOf != null) {
            return ResponseEntity.ok(valuationService.getValuationAsOf(locationId, sku, asOf));
        }
        return ResponseEntity.ok(valuationService.getValuation(locationId, sku));
    }
}
