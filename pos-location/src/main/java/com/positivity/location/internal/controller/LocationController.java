package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.CreateLocationRequest;
import com.positivity.location.internal.dto.LocationDto;
import com.positivity.location.internal.dto.UpdateLocationRequest;
import com.positivity.location.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Location API", description = "Operations related to locations and their relationships")
@RestController
@RequestMapping("/v1/locations")
public class LocationController {

    private final LocationService locationService;

    public LocationController(@NonNull LocationService locationService) {
        this.locationService = locationService;
    }

    @Operation(summary = "Get all locations", description = "Retrieve a list of all locations.")
    @ApiResponse(responseCode = "200", description = "List of locations returned successfully.")
    @EmitEvent(id = "LOCATION_LIST", apiVersion = "1")
    @GetMapping
    public ResponseEntity<List<LocationDto>> listLocations() {
        return ResponseEntity.ok(locationService.listLocations());
    }

    @Operation(summary = "Get location by ID", description = "Retrieve a location by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Location found and returned.")
    @ApiResponse(responseCode = "404", description = "Location not found.")
    @EmitEvent(id = "LOCATION_GET", apiVersion = "1")
    @GetMapping("/{locationId}")
    public ResponseEntity<LocationDto> getLocationById(
            @Parameter(description = "ID of the location to retrieve", example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable UUID locationId) {
        return ResponseEntity.ok(locationService.getLocationById(locationId));
    }

    @Operation(summary = "Create a new location", description = "Add a new location to the system.")
    @ApiResponse(responseCode = "201", description = "Location created successfully.")
    @ApiResponse(responseCode = "400", description = "Validation error.")
    @ApiResponse(responseCode = "409", description = "Duplicate location error.")
    @EmitEvent(id = "LOCATION_CREATED", apiVersion = "1")
    @PostMapping
    public ResponseEntity<LocationDto> createLocation(
            @Parameter(description = "Create location payload") @Valid @RequestBody CreateLocationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locationService.createLocation(request));
    }

    @Operation(summary = "Update an existing location", description = "Update the details of an existing location.")
    @ApiResponse(responseCode = "200", description = "Location updated successfully.")
    @ApiResponse(responseCode = "400", description = "Validation error.")
    @ApiResponse(responseCode = "404", description = "Location not found.")
    @ApiResponse(responseCode = "409", description = "Duplicate location error.")
    @EmitEvent(id = "LOCATION_UPDATED", apiVersion = "1")
    @PutMapping("/{locationId}")
    public ResponseEntity<LocationDto> updateLocation(
            @Parameter(description = "ID of the location to update", example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable UUID locationId,
            @Parameter(description = "Update location payload") @Valid @RequestBody UpdateLocationRequest request) {
        return ResponseEntity.ok(locationService.updateLocation(locationId, request));
    }

    @Operation(summary = "Delete a location", description = "Delete a location by its unique ID.")
    @ApiResponse(responseCode = "204", description = "Location deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Location not found.")
    @EmitEvent(id = "LOCATION_DELETED", apiVersion = "1")
    @DeleteMapping("/{locationId}")
    public ResponseEntity<Void> deleteLocationById(
            @Parameter(description = "ID of the location to delete", example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable UUID locationId) {
        locationService.deleteLocation(locationId);
        return ResponseEntity.noContent().build();
    }
}
