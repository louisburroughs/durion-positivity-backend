package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.StorageLocationPatchRequest;
import com.positivity.location.internal.dto.StorageLocationRequest;
import com.positivity.location.internal.dto.StorageLocationResponse;
import com.positivity.location.internal.dto.StorageLocationTopologyResponse;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.StorageLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for storage location topology endpoints.
 *
 * Issue: CAP-214 #39
 */
@RestController
@RequestMapping("/v1/locations/{siteId}/storage-locations")
@RequiredArgsConstructor
@Tag(name = "Storage Location API", description = "Operations for managing storage locations within a site")
public class StorageLocationController {

    private static final String STORAGE_LOCATION_EXAMPLE = """
            {"name":"Aisle 3 Bin 12",
             "barcode":"SL-000312",
             "type":"BIN",
             "parentStorageLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "storageCategoryCode":"SMALL_PARTS_BIN",
             "hazardContainment":false,
             "allowNewProduct":"MIXED",
             "capacity":{"maxUnits":100},
             "temperature":{"min":2,"max":8,"unit":"C"}}
            """;

    private final StorageLocationService storageLocationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + LocationPermissions.WRITE + "')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createStorageLocation",
            summary = "Create a Storage Location Within Site",
            description = """
                    Creates a storage location (FLOOR, SHELF, BIN, CAGE or TRUCK) inside a site, always starting \
                    in ACTIVE status.
                    Use this tool when adding storage topology to a site; do not use patchStorageLocation, which \
                    modifies an existing node, and use createBay for vehicle service bays rather than inventory \
                    storage.
                    Preconditions: the site must exist, the name must be unique within the site \
                    (case-insensitive), any barcode must be unique within the site, and a \
                    parentStorageLocationId must reference a storage location of the same site.
                    Required inputs: name and type; barcode, parentStorageLocationId, capacity and temperature \
                    (free-form JSON objects) are optional.
                    The optional storageCategoryCode declares the putaway capability — what the location is fit \
                    to hold (TIRE_RACK, OIL_STORAGE, BATTERY_RACK, SMALL_PARTS_BIN, BULK_FLOOR, STAGING, \
                    QUARANTINE, GENERAL) — which is independent of type: a tire rack and a bulk pallet area are \
                    both FLOOR. Omitting it leaves the capability undeclared, and the response reports GENERAL, \
                    which accepts every catalog category. hazardContainment (default false) and allowNewProduct \
                    (MIXED, SAME_PRODUCT_ONLY or EMPTY_ONLY; default MIXED) are the other putaway attributes.
                    Emits a LOCATION_STORAGE_LOCATION_CREATE event and publishes a storage-location fact for \
                    replica consumers.
                    Returns 404 when the site does not exist, 409 when the name or barcode is already used in the \
                    site, and 400 when the parent storage location is unknown or belongs to a different site.
                    """)
    @ApiResponse(responseCode = "201", description = "Storage location created")
    @ApiResponse(responseCode = "400", description = "Parent storage location unknown or in another site")
    @ApiResponse(responseCode = "404", description = "Site not found")
    @ApiResponse(responseCode = "409", description = "Duplicate name or barcode within the site")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:write"})
    public StorageLocationResponse create(
            @PathVariable UUID siteId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Storage location to create, positioned in the site's topology via its"
                                    + " optional parent reference.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Refrigerated bin",
                                                            value = STORAGE_LOCATION_EXAMPLE)))
                    @RequestBody
                    StorageLocationRequest request) {
        return storageLocationService.createStorageLocation(siteId, request);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + LocationPermissions.READ + "')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_LIST", apiVersion = "1")
    @Operation(operationId = "listStorageLocations", summary = "List Storage Locations of a Site", description = """
                    Lists the storage locations of a site as a page, optionally filtered by type and status.
                    Use this tool for paginated browsing; use getStorageLocationTopology instead when the \
                    complete unpaginated hierarchy of the site is needed.
                    Preconditions: none; an unknown site id yields an empty page rather than an error.
                    Required inputs: siteId (UUID) as a path parameter; type (FLOOR, SHELF, BIN, CAGE, TRUCK) and \
                    status (ACTIVE, INACTIVE, MAINTENANCE, QUARANTINED) filters are optional, and standard page, \
                    size and sort parameters control paging.
                    Emits a LOCATION_STORAGE_LOCATION_LIST event; no state changes.
                    Returns 200 with a page of storage locations regardless of whether any match the filters.
                    """)
    @ApiResponse(responseCode = "200", description = "Storage locations listed")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    public Page<StorageLocationResponse> list(
            @PathVariable UUID siteId,
            @RequestParam(required = false) StorageLocationType type,
            @RequestParam(required = false) StorageLocationStatus status,
            @ParameterObject Pageable pageable) {
        return storageLocationService.listStorageLocations(siteId, type, status, pageable);
    }

    /**
     * Unpaginated topology listing for rollup consumers (FR-3).
     *
     * Issue: CAP-214 #655
     */
    @GetMapping("/topology")
    @PreAuthorize("hasAuthority('" + LocationPermissions.READ + "')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_TOPOLOGY", apiVersion = "1")
    @Operation(
            operationId = "getStorageLocationTopology",
            summary = "Get Full Storage Location Topology",
            description = """
                    Returns every storage location of a site, regardless of status, as a flat unpaginated list \
                    with id, name, type, status and parentStorageLocationId.
                    Use this tool when a rollup or topology consumer needs the whole storage tree in one call; \
                    use listStorageLocations instead for paginated, filtered browsing.
                    Preconditions: the site must exist.
                    Required inputs: siteId (UUID) as a path parameter; there is no filtering.
                    Emits a LOCATION_STORAGE_LOCATION_TOPOLOGY event; no state changes.
                    Returns 404 when the site does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Storage location topology returned")
    @ApiResponse(responseCode = "404", description = "Site not found")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    public List<StorageLocationTopologyResponse> topology(
            @Parameter(description = "Site identifier", required = true) @PathVariable UUID siteId) {
        return storageLocationService.listStorageLocationTopology(siteId);
    }

    @GetMapping("/{storageLocationId}")
    @PreAuthorize("hasAuthority('" + LocationPermissions.READ + "')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_GET", apiVersion = "1")
    @Operation(operationId = "getStorageLocation", summary = "Get a Storage Location by Identifier", description = """
                    Returns a single storage location of a site, including capacity and temperature attributes \
                    and its parent reference.
                    Use this tool when the storage location id and its site are both known; use \
                    validateStorageLocation instead when only existence, active state and site ownership must be \
                    checked without knowing the site.
                    Preconditions: the storage location must exist and belong to the supplied site.
                    Required inputs: siteId and storageLocationId (UUIDs) as path parameters.
                    Emits a LOCATION_STORAGE_LOCATION_GET event; no state changes.
                    Returns 404 when no storage location with that id exists under the site.
                    """)
    @ApiResponse(responseCode = "200", description = "Storage location returned")
    @ApiResponse(responseCode = "404", description = "Storage location not found")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    public StorageLocationResponse get(@PathVariable UUID siteId, @PathVariable UUID storageLocationId) {
        return storageLocationService.getStorageLocation(siteId, storageLocationId);
    }

    @PatchMapping("/{storageLocationId}")
    @PreAuthorize("hasAuthority('" + LocationPermissions.WRITE + "')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_UPDATE", apiVersion = "1")
    @Operation(operationId = "patchStorageLocation", summary = "Patch Fields of a Storage Location", description = """
                    Applies a partial update to a storage location, covering rename, barcode, reparenting, \
                    capacity, temperature, putaway capability and status transitions.
                    Use this tool for all storage-location mutations after creation, including deactivation; do \
                    not use createStorageLocation, which adds a new node.
                    Preconditions: the storage location must exist in the site; a new name or barcode must be \
                    unique within the site, a new parent must belong to the same site without creating a cycle, \
                    and deactivating a node that still holds on-hand stock requires a \
                    destinationStorageLocationId naming an ACTIVE storage location of the same site to receive \
                    the transferred inventory.
                    Required inputs: siteId and storageLocationId (UUIDs) as path parameters and a body with at \
                    least one field; status accepts ACTIVE, INACTIVE, MAINTENANCE or QUARANTINED, \
                    storageCategoryCode accepts TIRE_RACK, OIL_STORAGE, BATTERY_RACK, SMALL_PARTS_BIN, \
                    BULK_FLOOR, STAGING, QUARANTINE or GENERAL, and allowNewProduct accepts MIXED, \
                    SAME_PRODUCT_ONLY or EMPTY_ONLY. Every capability field is independently omittable — an \
                    absent one is left unchanged rather than reset.
                    Emits a LOCATION_STORAGE_LOCATION_UPDATE event, publishes a storage-location fact, and on \
                    deactivation with stock triggers an atomic inventory transfer to the destination.
                    Returns 404 when the storage location or transfer destination does not exist, 409 when the \
                    name or barcode collides or the new parent would create a cycle, 400 when the parent is \
                    unknown or in another site, and 422 when deactivation needs a destination that is missing, \
                    inactive or the node itself.
                    """)
    @ApiResponse(responseCode = "200", description = "Storage location updated")
    @ApiResponse(responseCode = "400", description = "Parent storage location unknown or in another site")
    @ApiResponse(responseCode = "404", description = "Storage location not found")
    @ApiResponse(responseCode = "409", description = "Duplicate name or barcode, or reparenting would create a cycle")
    @ApiResponse(responseCode = "422", description = "Deactivation destination missing, inactive or invalid")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:write"})
    public StorageLocationResponse patch(
            @PathVariable UUID siteId,
            @PathVariable UUID storageLocationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Partial storage-location payload; only non-null fields are applied,"
                                    + " and destinationStorageLocationId is consumed only when deactivating a"
                                    + " node that still holds stock.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Deactivate with stock transfer",
                                                            value = """
                                                                    {"status":"INACTIVE",
                                                                     "destinationStorageLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a11"}
                                                                    """)))
                    @RequestBody
                    StorageLocationPatchRequest patch) {
        return storageLocationService.patchStorageLocation(siteId, storageLocationId, patch);
    }
}
