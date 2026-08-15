package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.dto.rollup.SiteInventoryRollupResponse;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.service.SiteInventoryRollupService;
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
@RequestMapping("/v1/inventory/sites")
@RequiredArgsConstructor
@Validated
@Tag(name = "Inventory Rollup", description = "Inventory quantities rolled up along the storage-location hierarchy")
public class SiteInventoryRollupController {

    private final SiteInventoryRollupService siteInventoryRollupService;

    @GetMapping("/{siteId}/inventory-rollup")
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.INVENTORY_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @Operation(
            operationId = "getSiteInventoryRollup",
            summary = "Get site inventory rollup",
            description = """
                    Returns on-hand, allocated and available quantities grouped and subtotaled along the \
                    storage-location hierarchy of one site, each node carrying its own quantities plus the \
                    rolled-up sum of itself and all descendants.
                    Use this tool for the storage-location tree inside a single site; do not use \
                    getLocationInventoryRollup, which aggregates across the descendant sites of a parent location \
                    (building, place or region) rather than within one site.
                    Preconditions: the site must exist in pos-location, whose topology is fetched at request time; \
                    ledger rows at location ids unknown to the site are excluded — there is no unassigned bucket.
                    Required inputs: siteId (UUID) path parameter; sku is an optional filter, depth (minimum 1, \
                    where 1 returns root storage locations only) limits the returned tree, and includeEmpty \
                    defaults to false.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when the site is not found, 400 for invalid parameters, and 503 when the location \
                    service is unavailable.
                    """,
            tags = {"Inventory Rollup"})
    @ApiResponse(
            responseCode = "200",
            description = "Rollup returned (empty nodes list when the site has no storage locations)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SiteInventoryRollupResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid parameters")
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required on-hand view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Site not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Location service unavailable; retry later",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SiteInventoryRollupResponse> getSiteInventoryRollup(
            @Parameter(description = "Site (Location) identifier", required = true) @PathVariable UUID siteId,
            @Parameter(description = "Optional product SKU filter") @RequestParam(required = false) String sku,
            @Parameter(description = "Maximum tree depth to return (1 = root storage locations only)")
                    @RequestParam(required = false)
                    @Min(1)
                    Integer depth,
            @Parameter(description = "Include storage locations with zero on-hand and zero allocations")
                    @RequestParam(required = false, defaultValue = "false")
                    boolean includeEmpty) {
        return ResponseEntity.ok(siteInventoryRollupService.getSiteInventoryRollup(siteId, sku, depth, includeEmpty));
    }
}
