package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.StorageLocationValidationResponseDTO;
import com.positivity.location.service.StorageLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Validation endpoint for inter-service storage location checks.
 */
@RestController
@RequestMapping("/v1/storage-locations")
@RequiredArgsConstructor
public class StorageLocationValidationController {

    private final StorageLocationService storageLocationService;

    @GetMapping("/{storageLocationId}/validation")
    @Operation(
            summary = "Validate storage location reference",
            description = "Returns existence, active status, and site ownership for a storage location ID.")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Validation payload returned"),
                @ApiResponse(responseCode = "400", description = "Invalid storageLocationId format"),
                @ApiResponse(responseCode = "403", description = "Forbidden - missing location:read authority")
            })
    @PreAuthorize("hasAuthority('location:read')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_VALIDATE", apiVersion = "1")
    public StorageLocationValidationResponseDTO validateStorageLocation(
            @Parameter(description = "Storage location identifier", required = true) @PathVariable
                    UUID storageLocationId) {
        return storageLocationService.getStorageLocationValidation(storageLocationId);
    }
}
