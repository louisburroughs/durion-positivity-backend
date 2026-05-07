package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.dto.LocationDto;
import com.positivity.inventory.internal.dto.LocationZoneDto;
import com.positivity.inventory.internal.dto.StorageLocationDto;
import com.positivity.inventory.service.InventoryReferenceDataService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
@Tag(name = "Inventory Reference Data", description = "Inventory reference-data read endpoints")
public class InventoryReferenceDataController {

    private final InventoryReferenceDataService inventoryReferenceDataService;

    @GetMapping("/locations")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:view"})
    @PreAuthorize("hasAuthority('inventory:location:view')")
    @Operation(
            operationId = "listInventoryLocations",
            summary = "List inventory locations",
            description = "Returns paged inventory locations.",
            tags = {"Inventory Reference Data"})
    @ApiResponse(
            responseCode = "200",
            description = "Locations returned (paged)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(description = "Page of inventory locations")))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required location view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<LocationDto>> listLocations(
            @RequestParam(required = false) UUID siteId, @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(inventoryReferenceDataService.listLocations(siteId, pageable));
    }

    @GetMapping("/storage-locations")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:view"})
    @PreAuthorize("hasAuthority('inventory:location:view')")
    @Operation(
            operationId = "listInventoryStorageLocations",
            summary = "List inventory storage locations",
            description = "Returns paged inventory storage locations.",
            tags = {"Inventory Reference Data"})
    @ApiResponse(
            responseCode = "200",
            description = "Storage locations returned (paged)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(description = "Page of inventory storage locations")))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required location view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<StorageLocationDto>> listStorageLocations(
            @RequestParam(required = false) UUID locationId, @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(inventoryReferenceDataService.listStorageLocations(locationId, pageable));
    }

    @GetMapping("/location-zones")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:view"})
    @PreAuthorize("hasAuthority('inventory:location:view')")
    @Operation(
            operationId = "listInventoryLocationZones",
            summary = "List inventory location zones",
            description = "Returns paged inventory location zones.",
            tags = {"Inventory Reference Data"})
    @ApiResponse(
            responseCode = "200",
            description = "Location zones returned (paged)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(description = "Page of inventory location zones")))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required location view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<LocationZoneDto>> listLocationZones(
            @RequestParam(required = false) UUID locationId, @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(inventoryReferenceDataService.listLocationZones(locationId, pageable));
    }
}
