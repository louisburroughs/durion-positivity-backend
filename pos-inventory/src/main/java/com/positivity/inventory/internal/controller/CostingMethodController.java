package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.costing.CostingMethodConfigRequest;
import com.positivity.inventory.internal.dto.costing.CostingMethodConfigResponse;
import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactResponse;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.service.CostingMethodConfigService;
import com.positivity.inventory.service.SkuCategoryCutoverService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final SkuCategoryCutoverService skuCategoryCutoverService;

    @GetMapping
    @EmitEvent(id = "INVENTORY_VALUATION_METHOD_LIST", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.LOCATION_ADMIN + "')")
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
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.LOCATION_ADMIN + "')")
    @Operation(
            operationId = "upsertCostingMethodConfig",
            summary = "Upsert costing method configuration",
            description = """
                    Creates or updates the costing method (STANDARD or AVERAGE) for one scope — SKU, SKU_CATEGORY \
                    or DEFAULT — reactivating the row and keeping at most one row per scope.
                    Use this tool to switch which method a scope resolves to going forward only; do not use it \
                    expecting opening values to be restated — that cut-over is createRevaluation, which this call \
                    deliberately does not perform.
                    Preconditions: none; note that the catalog replica has carried the product's category \
                    since #1514, and SKU_CATEGORY resolution is gated by \
                    pos.inventory.sku-category.resolve-from-replica, which defaults off — call \
                    reportSkuCategoryImpact before enabling it.
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

    @DeleteMapping("/{configId}")
    @EmitEvent(id = "INVENTORY_VALUATION_METHOD_DEACTIVATE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.LOCATION_ADMIN + "')")
    @Operation(
            operationId = "deactivateCostingMethodConfig",
            summary = "Deactivate costing method configuration",
            description = """
                    Deactivates one costing method configuration row so it stops participating in method \
                    resolution; this is a soft delete and the row is never removed.
                    Use this tool to retire a scope override and fall back to the next precedence level — this \
                    is how a SKU_CATEGORY row is taken out of scope before enabling \
                    pos.inventory.sku-category.resolve-from-replica; do not use upsertCostingMethodConfig with \
                    a different method when the intent is to remove the override entirely.
                    Preconditions: the configuration row must exist; deactivating an already inactive row is a \
                    no-op that returns the row and writes no second audit entry.
                    Required inputs: configId (UUID) path parameter; there is no request body.
                    Emits an INVENTORY_VALUATION_METHOD_DEACTIVATE event, and records a DEACTIVATED row in the \
                    cost method change log; subsequent postings fall back to DEFAULT or the deployment default \
                    pos.inventory.valuation.default-method.
                    Returns 404 when no configuration exists for the supplied id.
                    """,
            tags = {"Valuation Methods"})
    @ApiResponse(
            responseCode = "200",
            description = "Costing method configuration deactivated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CostingMethodConfigResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required permission",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Costing method configuration not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CostingMethodConfigResponse> deactivateConfig(
            @Parameter(
                            description = "Identifier of the costing method configuration row to deactivate",
                            required = true)
                    @PathVariable
                    UUID configId) {
        return ResponseEntity.ok(costingMethodConfigService.deactivateConfig(configId));
    }

    @GetMapping("/sku-category-impact")
    @EmitEvent(id = "INVENTORY_VALUATION_METHOD_SKU_CATEGORY_IMPACT", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.LOCATION_ADMIN + "')")
    @Operation(
            operationId = "reportSkuCategoryImpact",
            summary = "Report the SKU_CATEGORY resolution impact",
            description = """
                    Reports which SKUs would change costing method, from what to what, if \
                    pos.inventory.sku-category.resolve-from-replica were enabled, and which SKUs would start \
                    resolving their sourcing strategy from a SKU_CATEGORY row.
                    Use this tool as the pre-flight for that flag: it is valid and meaningful while the flag is \
                    still OFF, which is the only moment the answer is actionable, because it reads the catalog \
                    replica directly instead of going through the SPI the flag gates. Do not use \
                    listCostingMethodConfigs for this — it lists what is configured, not what would change.
                    Preconditions: none beyond the inventory:location:admin authority; the catalog product \
                    replica should be fully populated first, or the report understates the impact.
                    Required inputs: none; there is no request body, paging or filtering.
                    Emits an INVENTORY_VALUATION_METHOD_SKU_CATEGORY_IMPACT audit event; nothing is changed.
                    Returns 200 with zero counts when no SKU_CATEGORY configuration exists, meaning the flag \
                    would change nothing for costing. The full cut-over procedure is in \
                    docs/OPERATIONS_RUNBOOK.md.
                    """,
            tags = {"Valuation Methods"})
    @ApiResponse(
            responseCode = "200",
            description = "SKU_CATEGORY impact report returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SkuCategoryImpactResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required permission",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SkuCategoryImpactResponse> reportSkuCategoryImpact() {
        return ResponseEntity.ok(skuCategoryCutoverService.impact());
    }
}
