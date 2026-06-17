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
            description = "Returns on-hand quantity aggregated for a storage location.",
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
            @Parameter(description = "Product SKU filter") @RequestParam(required = false) String sku) {
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
            description = "Returns the on-hand stock items (positive quantity) at a storage location.",
            tags = {"Inventory Locations"})
    @ApiResponse(
            responseCode = "200",
            description = "Location inventory contents returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationInventoryItemsResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid location identifier")
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required on-hand view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<LocationInventoryItemsResponse> listLocationInventoryItems(
            @Parameter(description = "Storage location identifier", required = true) @PathVariable UUID locationId) {
        return ResponseEntity.ok(locationInventoryInquiryService.listLocationInventoryItems(locationId));
    }
}
