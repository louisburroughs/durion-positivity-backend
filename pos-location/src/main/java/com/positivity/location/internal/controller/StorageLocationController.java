package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.StorageLocationPatchRequest;
import com.positivity.location.internal.dto.StorageLocationRequest;
import com.positivity.location.internal.dto.StorageLocationResponse;
import com.positivity.location.internal.dto.StorageLocationTopologyResponse;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.service.StorageLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

    private final StorageLocationService storageLocationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('location:write')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_CREATE", apiVersion = "1")
    @Operation(
            summary = "Create storage location",
            description = "Create a storage location within a site using the provided topology and status details")
    @ApiResponse(responseCode = "201", description = "Storage location created")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:write"})
    public StorageLocationResponse create(@PathVariable UUID siteId, @RequestBody StorageLocationRequest request) {
        return storageLocationService.createStorageLocation(siteId, request);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('location:read')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_LIST", apiVersion = "1")
    @Operation(
            summary = "List storage locations",
            description = "List storage locations for a site with optional type and status filtering")
    @ApiResponse(responseCode = "200", description = "Storage locations listed")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    public Page<StorageLocationResponse> list(
            @PathVariable UUID siteId,
            @RequestParam(required = false) StorageLocationType type,
            @RequestParam(required = false) StorageLocationStatus status,
            Pageable pageable) {
        return storageLocationService.listStorageLocations(siteId, type, status, pageable);
    }

    /**
     * Unpaginated topology listing for rollup consumers (FR-3).
     *
     * Issue: CAP-214 #655
     */
    @GetMapping("/topology")
    @PreAuthorize("hasAuthority('location:read')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_TOPOLOGY", apiVersion = "1")
    @Operation(
            summary = "Get storage location topology",
            description = "Return every storage location of a site, regardless of status, as a flat unpaginated "
                    + "list with id, name, type, status, and parentStorageLocationId for topology consumers")
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
    @PreAuthorize("hasAuthority('location:read')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_GET", apiVersion = "1")
    @Operation(
            summary = "Get storage location",
            description = "Retrieve a single storage location for a site by its storage location identifier")
    @ApiResponse(responseCode = "200", description = "Storage location returned")
    @ApiResponse(responseCode = "404", description = "Storage location not found")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    public StorageLocationResponse get(@PathVariable UUID siteId, @PathVariable UUID storageLocationId) {
        return storageLocationService.getStorageLocation(siteId, storageLocationId);
    }

    @PatchMapping("/{storageLocationId}")
    @PreAuthorize("hasAuthority('location:write')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_UPDATE", apiVersion = "1")
    @Operation(
            summary = "Patch storage location",
            description = "Patch an existing storage location for a site using the provided partial updates")
    @ApiResponse(responseCode = "200", description = "Storage location updated")
    @ApiResponse(responseCode = "404", description = "Storage location not found")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:write"})
    public StorageLocationResponse patch(
            @PathVariable UUID siteId,
            @PathVariable UUID storageLocationId,
            @RequestBody StorageLocationPatchRequest patch) {
        return storageLocationService.patchStorageLocation(siteId, storageLocationId, patch);
    }
}
