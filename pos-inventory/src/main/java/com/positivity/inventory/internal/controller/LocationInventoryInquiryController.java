package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.internal.dto.LocationInventoryItemsResponse;
import com.positivity.inventory.service.LocationInventoryInquiryService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/locations")
@Tag(name = "Inventory Locations", description = "Read inventory facts for storage locations")
public class LocationInventoryInquiryController {

    private final LocationInventoryInquiryService locationInventoryInquiryService;

    public LocationInventoryInquiryController(LocationInventoryInquiryService locationInventoryInquiryService) {
        this.locationInventoryInquiryService = locationInventoryInquiryService;
    }

    @GetMapping("/{locationId}/inventory-inquiry")
    @PreAuthorize("hasAuthority('inventory:on_hand:view')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @Operation(
            operationId = "getLocationInventory",
            summary = "Get location inventory summary",
            description = """
                    Returns the aggregated on-hand quantity for one storage location, optionally filtered to a \
                    single SKU, or the historical on-hand as of a past instant by direct ledger aggregation.
                    Use this tool for a quantity summary of one location; use listLocationInventoryItems instead \
                    for the per-SKU contents, and use getSiteInventoryRollup for hierarchy subtotals across a whole \
                    site.
                    Preconditions: none for the current view; an asOf query additionally requires the \
                    inventory:ledger:view authority.
                    Required inputs: locationId (UUID) path parameter; sku and asOf (ISO-8601 instant) are \
                    optional, and with asOf the availableToPromiseQuantity is null because historical allocation \
                    state is not reliably reconstructable from ATP-neutral ledger events.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 422 with AS_OF_IN_FUTURE when asOf lies in the future, 403 when asOf is used without \
                    inventory:ledger:view, and 400 for an invalid location identifier.
                    """,
            tags = {"Inventory Locations"})
    @ApiResponse(
            responseCode = "200",
            description = "Location inventory returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationInventoryInquiryResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid location identifier")
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required on-hand view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<LocationInventoryInquiryResponse> getLocationInventory(
            @Parameter(description = "Storage location identifier", required = true) @PathVariable UUID locationId,
            @Parameter(description = "Product SKU filter") @RequestParam(required = false) String sku,
            @Parameter(
                            description =
                                    "Optional historical instant (ISO-8601). Returns on-hand as of this instant by "
                                            + "direct ledger aggregation; availableToPromiseQuantity is null. Requires "
                                            + "'inventory:ledger:view'; future instants are rejected (422).")
                    @RequestParam(required = false)
                    Instant asOf) {
        if (asOf != null) {
            return ResponseEntity.ok(locationInventoryInquiryService.getLocationInventoryAsOf(locationId, sku, asOf));
        }
        return ResponseEntity.ok(locationInventoryInquiryService.getLocationInventory(locationId, sku));
    }

    @GetMapping("/{locationId}/inventory-items")
    @PreAuthorize("hasAuthority('inventory:on_hand:view')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @Operation(
            operationId = "listLocationInventoryItems",
            summary = "List location inventory contents",
            description = """
                    Returns the stock items with positive on-hand at one storage location, or the historical \
                    contents as of a past instant by direct ledger aggregation.
                    Use this tool to see what a location holds per SKU; use getLocationInventory instead for the \
                    aggregated quantity summary of the location.
                    Preconditions: none for the current view; an asOf query additionally requires the \
                    inventory:ledger:view authority.
                    Required inputs: locationId (UUID) path parameter; asOf (ISO-8601 instant) is optional.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 422 with AS_OF_IN_FUTURE when asOf lies in the future, 403 when asOf is used without \
                    inventory:ledger:view, and 400 for an invalid location identifier.
                    """,
            tags = {"Inventory Locations"})
    @ApiResponse(
            responseCode = "200",
            description = "Location inventory contents returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationInventoryItemsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid location identifier",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required on-hand view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<LocationInventoryItemsResponse> listLocationInventoryItems(
            @Parameter(description = "Storage location identifier", required = true) @PathVariable UUID locationId,
            @Parameter(
                            description = "Optional historical instant (ISO-8601). Returns the location contents as of "
                                    + "this instant by direct ledger aggregation. Requires "
                                    + "'inventory:ledger:view'; future instants are rejected (422).")
                    @RequestParam(required = false)
                    Instant asOf) {
        if (asOf != null) {
            return ResponseEntity.ok(locationInventoryInquiryService.listLocationInventoryItemsAsOf(locationId, asOf));
        }
        return ResponseEntity.ok(locationInventoryInquiryService.listLocationInventoryItems(locationId));
    }
}
