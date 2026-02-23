package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.StorageLocationPatchRequest;
import com.positivity.location.internal.dto.StorageLocationRequest;
import com.positivity.location.internal.dto.StorageLocationResponse;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.service.StorageLocationService;
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
public class StorageLocationController {

    private final StorageLocationService storageLocationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('location:write')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_CREATE", apiVersion = "1")
    public StorageLocationResponse create(@PathVariable UUID siteId, @RequestBody StorageLocationRequest request) {
        return storageLocationService.createStorageLocation(siteId, request);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('location:read')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_LIST", apiVersion = "1")
    public Page<StorageLocationResponse> list(@PathVariable UUID siteId,
            @RequestParam(required = false) StorageLocationType type,
            Pageable pageable) {
        return storageLocationService.listStorageLocations(siteId, type, pageable);
    }

    @GetMapping("/{storageLocationId}")
    @PreAuthorize("hasAuthority('location:read')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_GET", apiVersion = "1")
    public StorageLocationResponse get(@PathVariable UUID siteId, @PathVariable UUID storageLocationId) {
        return storageLocationService.getStorageLocation(siteId, storageLocationId);
    }

    @PatchMapping("/{storageLocationId}")
    @PreAuthorize("hasAuthority('location:write')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_UPDATE", apiVersion = "1")
    public StorageLocationResponse patch(@PathVariable UUID siteId,
            @PathVariable UUID storageLocationId,
            @RequestBody StorageLocationPatchRequest patch) {
        return storageLocationService.patchStorageLocation(siteId, storageLocationId, patch);
    }
}
