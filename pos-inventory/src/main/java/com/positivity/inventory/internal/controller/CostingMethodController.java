package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.costing.CostingMethodConfigRequest;
import com.positivity.inventory.internal.dto.costing.CostingMethodConfigResponse;
import com.positivity.inventory.service.CostingMethodConfigService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
            operationId = "listCostingMethodConfigs",
            summary = "List costing method configurations",
            description = """
                    Returns every costing method configuration row, active and inactive, ordered by scope type and \
                    scope value.
                    Use this tool to inspect which costing method each scope resolves to — precedence at posting \
                    time is SKU, then SKU_CATEGORY, then DEFAULT, then the deployment default \
                    pos.inventory.valuation.default-method (AVERAGE); do not use getInventoryValuation, which \
                    reports the values computed under those methods.
                    Preconditions: none beyond the inventory:location:admin authority.
                    Required inputs: none; there is no request body, paging or filtering.
                    Emits an INVENTORY_VALUATION_METHOD_LIST audit event; no configuration changes.
                    Returns 200 with an empty array when nothing is configured, meaning every SKU falls through \
                    to the deployment default.
                    """,
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
            operationId = "upsertCostingMethodConfig",
            summary = "Upsert costing method configuration",
            description = """
                    Creates or updates the costing method (STANDARD or AVERAGE) for one scope — SKU, SKU_CATEGORY \
                    or DEFAULT — reactivating the row and keeping at most one row per scope.
                    Use this tool to switch which method a scope resolves to going forward only; do not use it \
                    expecting opening values to be restated — that cut-over is createRevaluation, which this call \
                    deliberately does not perform.
                    Preconditions: none; note that SKU_CATEGORY rows are stored but unresolvable until the \
                    catalog replica carries a category, so resolution skips them to DEFAULT.
                    Required inputs: scopeType, method, and scopeValue — the stock item id for SKU, the category \
                    string for SKU_CATEGORY, and omitted for DEFAULT.
                    Emits an INVENTORY_VALUATION_METHOD_UPSERT event, and an effective method change is recorded \
                    as a who/when/from/to row in the cost method change log.
                    Returns 400 when scopeValue is supplied for DEFAULT scope or missing for SKU or SKU_CATEGORY \
                    scope.
                    """,
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Costing method to apply at one scope; the scope key rules depend on"
                                    + " scopeType.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Per-SKU standard costing", value = """
                                                                    {"scopeType":"SKU",
                                                                     "scopeValue":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "method":"STANDARD"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CostingMethodConfigRequest request) {
        return ResponseEntity.ok(costingMethodConfigService.upsertConfig(request));
    }
}
