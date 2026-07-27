package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.costing.CostingMethodConfigRequest;
import com.positivity.inventory.internal.dto.costing.CostingMethodConfigResponse;
import com.positivity.inventory.service.CostingMethodConfigService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for costing-method configuration (odoo-parity J1, issue
 * #1048; ADR-0048). Guarded by {@code inventory:location:admin} — the same
 * administration permission H1 reuses for sourcing-strategy config; no new
 * permission string is introduced.
 *
 * <p>Upsert applies the resolved method going forward and records a
 * who/when/from/to row in the change log. Restating opening values on a method
 * switch (the revaluation cut-over) is the J4 revaluation workflow, not here.
 */
@RestController
@RequestMapping("/v1/inventory/valuation/methods")
@RequiredArgsConstructor
@Tag(name = "Valuation Methods", description = "Costing method configuration endpoints (odoo-parity J1)")
public class CostingMethodController {

    private final CostingMethodConfigService costingMethodConfigService;

    @GetMapping
    @EmitEvent(id = "INVENTORY_VALUATION_METHOD_LIST", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @PreAuthorize("hasAuthority('inventory:location:admin')")
    @Operation(
            summary = "List costing method configurations",
            description = "Lists all costing method configuration rows (active and inactive), ordered by scope."
                    + " Resolution precedence at posting time is SKU, then SKU_CATEGORY, then DEFAULT, then the"
                    + " deployment default pos.inventory.valuation.default-method (AVERAGE).",
            tags = {"Valuation Methods"})
    @ApiResponse(
            responseCode = "200",
            description = "Costing method configurations returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = CostingMethodConfigResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required permission",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<CostingMethodConfigResponse>> listConfigs() {
        return ResponseEntity.ok(costingMethodConfigService.listConfigs());
    }

    @PutMapping
    @EmitEvent(id = "INVENTORY_VALUATION_METHOD_UPSERT", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @PreAuthorize("hasAuthority('inventory:location:admin')")
    @Operation(
            summary = "Upsert costing method configuration",
            description = "Creates or updates the costing method for one scope (SKU, SKU_CATEGORY, or DEFAULT) and"
                    + " reactivates it. A method change is recorded in the who/when/from/to change log. At most one"
                    + " row exists per scope. The new method applies going forward only; opening-value restatement"
                    + " is the J4 revaluation workflow.",
            tags = {"Valuation Methods"})
    @ApiResponse(
            responseCode = "200",
            description = "Costing method configuration upserted",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CostingMethodConfigResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required permission",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CostingMethodConfigResponse> upsertConfig(
            @Valid @RequestBody CostingMethodConfigRequest request) {
        return ResponseEntity.ok(costingMethodConfigService.upsertConfig(request));
    }
}
