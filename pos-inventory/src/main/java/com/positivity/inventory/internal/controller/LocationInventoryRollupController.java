package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.dto.rollup.LocationInventoryRollupResponse;
import com.positivity.inventory.service.LocationInventoryRollupService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/locations")
@RequiredArgsConstructor
@Validated
@Tag(name = "Inventory Rollup", description = "Inventory quantities rolled up along the storage-location hierarchy")
public class LocationInventoryRollupController {

    private static final String EXPAND_TREE = "tree";

    private final LocationInventoryRollupService locationInventoryRollupService;

    @GetMapping("/{locationId}/inventory-rollup")
    @PreAuthorize("hasAuthority('inventory:on_hand:view')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @Operation(
            operationId = "getLocationInventoryRollup",
            summary = "Get parent-location inventory rollup",
            description = "Aggregates inventory totals across all descendant sites of a parent location"
                    + " (building/place/region) along a typed parent chain (default PHYSICAL, ADR-0016)."
                    + " Returns per-site summaries plus a grand total. With expand=tree, each site entry"
                    + " inlines its full storage-location rollup tree; expansion is rejected with 422 when"
                    + " the descendant site count exceeds the configured cap"
                    + " (pos.inventory.rollup.expand-site-cap, default 25). Without expand, summaries are computed"
                    + " sequentially for every descendant regardless of count; prefer narrow parent locations"
                    + " for large hierarchies.",
            tags = {"Inventory Rollup"})
    @ApiResponse(
            responseCode = "200",
            description = "Rollup returned (empty sites list when the location has no descendants)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationInventoryRollupResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid parameters (unknown parentType, bad expand value)")
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required on-hand view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Location not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "expand=tree rejected: descendant site count exceeds the configured cap",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Location service unavailable; retry later",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<LocationInventoryRollupResponse> getLocationInventoryRollup(
            @Parameter(description = "Parent location identifier (building/place/region)", required = true)
                    @PathVariable
                    UUID locationId,
            @Parameter(description = "Typed parent chain to walk (default PHYSICAL)") @RequestParam(required = false)
                    String parentType,
            @Parameter(description = "Optional product SKU filter") @RequestParam(required = false) String sku,
            @Parameter(description = "Set to 'tree' to inline each site's full rollup tree")
                    @RequestParam(required = false)
                    String expand,
            @Parameter(description = "Maximum per-site tree depth (only with expand=tree)")
                    @RequestParam(required = false)
                    @Min(1)
                    Integer depth,
            @Parameter(description = "Include all-zero tree nodes (only with expand=tree)")
                    @RequestParam(required = false, defaultValue = "false")
                    boolean includeEmpty) {
        boolean expandTree = resolveExpand(expand);
        return ResponseEntity.ok(locationInventoryRollupService.getLocationInventoryRollup(
                locationId, parentType, sku, expandTree, depth, includeEmpty));
    }

    private boolean resolveExpand(String expand) {
        if (expand == null || expand.isBlank()) {
            return false;
        }
        if (EXPAND_TREE.equalsIgnoreCase(expand.trim())) {
            return true;
        }
        throw new IllegalArgumentException("Unsupported expand value '" + expand + "'; only 'tree' is supported");
    }
}
