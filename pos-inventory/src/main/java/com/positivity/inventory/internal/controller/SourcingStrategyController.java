package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.sourcing.SourcingStrategyConfigRequest;
import com.positivity.inventory.internal.dto.sourcing.SourcingStrategyConfigResponse;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.service.SourcingStrategyConfigService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
 * Admin endpoints for sourcing-strategy configuration (odoo-parity H1, issue
 * #1037). Guarded by {@code inventory:location:admin} — sourcing strategy is
 * location-topology administration, no new permission string is introduced.
 */
@RestController
@RequestMapping("/v1/inventory/sourcing-strategies")
@RequiredArgsConstructor
@Tag(name = "Sourcing Strategies", description = "Sourcing/removal strategy configuration endpoints")
public class SourcingStrategyController {

    private final SourcingStrategyConfigService sourcingStrategyConfigService;

    @GetMapping
    @EmitEvent(id = "INVENTORY_SOURCING_STRATEGY_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.LOCATION_ADMIN + "')")
    @Operation(
            operationId = "listSourcingStrategyConfigs",
            summary = "List sourcing strategy configurations",
            description = """
                    Lists every sourcing-strategy configuration row, active and inactive, ordered by scope type \
                    then scope value.
                    Use this tool to inspect removal-strategy configuration; use upsertSourcingStrategyConfig \
                    instead to change a scope, and deactivateSourcingStrategyConfig instead to retire one.
                    Preconditions: none; at most one row exists per scope.
                    Required inputs: none, and there is no paging or filtering.
                    Emits an INVENTORY_SOURCING_STRATEGY_LIST audit event; no configuration is changed.
                    Returns 200 with an empty array when nothing is configured, in which case decision-time \
                    resolution falls through SKU_CATEGORY, SITE and DEFAULT to the platform default FIFO.
                    """,
            tags = {"Sourcing Strategies"})
    @ApiResponse(
            responseCode = "200",
            description = "Sourcing strategy configurations returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = SourcingStrategyConfigResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required permission",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<SourcingStrategyConfigResponse>> listConfigs() {
        return ResponseEntity.ok(sourcingStrategyConfigService.listConfigs());
    }

    @PutMapping
    @EmitEvent(id = "INVENTORY_SOURCING_STRATEGY_UPSERT", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.LOCATION_ADMIN + "')")
    @Operation(
            operationId = "upsertSourcingStrategyConfig",
            summary = "Upsert sourcing strategy configuration",
            description = """
                    Creates or updates the sourcing strategy for one scope and reactivates the row; at most one \
                    row exists per scopeType and scopeValue pair.
                    Use this tool to set FIFO, FEFO, PROXIMITY or HIGHEST_STOCK at a scope; do not use \
                    deactivateSourcingStrategyConfig to change a strategy — an upsert on an inactive row \
                    reactivates it with the new strategy.
                    Preconditions: none beyond scope-value shape; note that FEFO falls back to FIFO while the SKU \
                    has no lot-expiry data, PROXIMITY falls back to FIFO when a decision has no reference \
                    location, and SKU_CATEGORY rows resolve only when \
                    pos.inventory.sku-category.resolve-from-replica is enabled, which defaults off — call \
                    reportSkuCategoryImpact before enabling it, since SKU_CATEGORY is the highest-precedence \
                    scope and would override SITE rows.
                    Required inputs: scopeType (SKU_CATEGORY, SITE or DEFAULT) and strategy; scopeValue must be \
                    the category string for SKU_CATEGORY, the site UUID as text for SITE, and must be omitted for \
                    DEFAULT.
                    Emits an INVENTORY_SOURCING_STRATEGY_UPSERT event; the change affects subsequent sourcing \
                    decisions immediately.
                    Returns 400 when scopeValue is missing for SITE or SKU_CATEGORY, is not a UUID for SITE, or is \
                    present for DEFAULT.
                    """,
            tags = {"Sourcing Strategies"})
    @ApiResponse(
            responseCode = "200",
            description = "Sourcing strategy configuration upserted",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SourcingStrategyConfigResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required permission",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SourcingStrategyConfigResponse> upsertConfig(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The scope being configured and the sourcing strategy to apply at it.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Proximity at one site", value = """
                                                                    {"scopeType":"SITE",
                                                                     "scopeValue":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
                                                                     "strategy":"PROXIMITY"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    SourcingStrategyConfigRequest request) {
        return ResponseEntity.ok(sourcingStrategyConfigService.upsertConfig(request));
    }

    @DeleteMapping("/{configId}")
    @EmitEvent(id = "INVENTORY_SOURCING_STRATEGY_DEACTIVATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.LOCATION_ADMIN + "')")
    @Operation(
            operationId = "deactivateSourcingStrategyConfig",
            summary = "Deactivate sourcing strategy configuration",
            description = """
                    Deactivates one sourcing-strategy configuration row so it stops participating in strategy \
                    resolution; this is a soft delete and the row is never removed.
                    Use this tool to retire a scope override and fall back to the next precedence level; do not \
                    use upsertSourcingStrategyConfig with a different strategy when the intent is to remove the \
                    override entirely.
                    Preconditions: the configuration row must exist; deactivating an already inactive row is a \
                    no-op that returns the row.
                    Required inputs: configId (UUID) path parameter; there is no request body.
                    Emits an INVENTORY_SOURCING_STRATEGY_DEACTIVATE event; subsequent decisions fall back to SITE, \
                    DEFAULT or the platform default FIFO.
                    Returns 404 when no configuration exists for the supplied id.
                    """,
            tags = {"Sourcing Strategies"})
    @ApiResponse(
            responseCode = "200",
            description = "Sourcing strategy configuration deactivated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SourcingStrategyConfigResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required permission",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Sourcing strategy configuration not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SourcingStrategyConfigResponse> deactivateConfig(
            @Parameter(description = "Sourcing strategy configuration identifier", required = true) @PathVariable
                    UUID configId) {
        return ResponseEntity.ok(sourcingStrategyConfigService.deactivateConfig(configId));
    }
}
