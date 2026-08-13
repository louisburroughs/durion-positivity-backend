package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.dto.LocationDto;
import com.positivity.inventory.internal.dto.LocationZoneDto;
import com.positivity.inventory.internal.dto.StorageLocationDto;
import com.positivity.inventory.service.InventoryReferenceDataService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
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
            description = """
                    Lists inventory locations as a page, served from the local location roster that is \
                    continuously synced from pos-location.
                    Use this tool to browse the roster the inventory module actually posts against; use \
                    listInventoryStorageLocations instead for bin-level records, and use triggerLocationSync when \
                    the roster looks stale.
                    Preconditions: none; the roster reflects the last synced state, not a live pos-location call.
                    Required inputs: none; the standard page/size parameters default to a page size of 20, and the \
                    siteId parameter is accepted but not yet applied because the roster carries no site linkage.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty page when the roster is empty, so an empty result is not an error \
                    condition.
                    """,
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
            description = """
                    Lists inventory storage locations as a page; the current implementation is a placeholder that \
                    always returns an empty page until the pos-location client integration lands.
                    Use this tool only to probe the future storage-location contract; use listInventoryLocations \
                    instead for the site-level roster that is actually populated.
                    Preconditions: none.
                    Required inputs: none; locationId and the page/size parameters (default size 20) are accepted \
                    but no data is served yet.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty page unconditionally in the current implementation.
                    """,
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
            description = """
                    Lists inventory location zones as a page; the current implementation is a placeholder that \
                    always returns an empty page until the pos-location client integration lands.
                    Use this tool only to probe the future location-zone contract; use listInventoryLocations \
                    instead for reference data that is actually served.
                    Preconditions: none.
                    Required inputs: none; locationId and the page/size parameters (default size 20) are accepted \
                    but no data is served yet.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty page unconditionally in the current implementation.
                    """,
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

    // pos-location owns this vocabulary (StorageLocationType, CAP-214 #39);
    // this mirror is pinned by the CAP-214 #40 frontend contract.
    private static final List<String> STORAGE_TYPES = List.of("FLOOR", "SHELF", "BIN", "CAGE", "TRUCK");

    @GetMapping("/meta/storage-types")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:view"})
    @PreAuthorize("hasAuthority('inventory:location:view')")
    @Operation(
            operationId = "listStorageTypes",
            summary = "List storage location types",
            description = """
                    Returns the supported storage location type codes — FLOOR, SHELF, BIN, CAGE and TRUCK — a \
                    static mirror of the vocabulary owned by pos-location.
                    Use this tool to populate storage-type pickers; do not use listInventoryStorageLocations, which \
                    lists storage-location records rather than the type vocabulary.
                    Preconditions: none; the list is pinned by the CAP-214 frontend contract and requires no lookup.
                    Required inputs: none; there is no request body, paging or filtering.
                    No events are emitted and no state changes; this is a read-only constant projection.
                    Returns 200 with the full five-code list on every call.
                    """,
            tags = {"Inventory Reference Data"})
    @ApiResponse(
            responseCode = "200",
            description = "Storage types returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(type = "string", example = "SHELF"))))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required location view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<String>> listStorageTypes() {
        return ResponseEntity.ok(STORAGE_TYPES);
    }
}
