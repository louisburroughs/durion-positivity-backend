package com.positivity.location.internal.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.PersonDTO;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.entity.ParentType;
import com.positivity.location.service.LocationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Tag(name = "Location API", description = "Operations related to locations and their relationships")
@RestController
@RequestMapping("/v1/locations")
@RequiredArgsConstructor
public class LocationController {
    private final LocationService locationService;

    @Operation(summary = "Get all locations", description = "Retrieve a list of all locations.")
    @ApiResponse(responseCode = "200", description = "List of locations returned successfully.")
    @GetMapping
    public List<Location> getAllLocations() {
        return locationService.getAllLocations();
    }

    @Operation(summary = "Get location by ID", description = "Retrieve a location by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Location found and returned.")
    @ApiResponse(responseCode = "404", description = "Location not found.")
    @GetMapping("/{locationId}")
    public ResponseEntity<Location> getLocationById(
            @Parameter(description = "ID of the location to retrieve", example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable UUID locationId) {
        return locationService.getLocationById(locationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new location", description = "Add a new location to the system.")
    @ApiResponse(responseCode = "200", description = "Location created successfully.")
    @EmitEvent(id = "LOCATION_LOCATION_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<Location> createLocation(
            @Parameter(description = "Location object to be created") @RequestBody Location location) {
        Location saved = locationService.saveLocation(location);
        return ResponseEntity.ok(saved);
    }

    @Operation(summary = "Update an existing location", description = "Update the details of an existing location.")

    @ApiResponse(responseCode = "200", description = "Location updated successfully.")
    @ApiResponse(responseCode = "404", description = "Location not found.")

    @EmitEvent(id = "LOCATION_LOCATION_UPDATE", apiVersion = "1")
    @PutMapping("/{locationId}")
    public ResponseEntity<Location> updateLocation(
            @Parameter(description = "ID of the location to update", example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable UUID locationId,
            @Parameter(description = "Updated location object") @RequestBody Location location) {
        if (!locationService.getLocationById(locationId).isPresent()) {
            return ResponseEntity.notFound().build();
        }
        location.setId(locationId);
        Location updated = locationService.saveLocation(location);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Delete a location", description = "Delete a location by its unique ID.")

    @ApiResponse(responseCode = "204", description = "Location deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Location not found.")
    @DeleteMapping("/{locationId}")
    public ResponseEntity<Void> deleteLocation(
            @Parameter(description = "ID of the location to delete", example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable UUID locationId) {
        if (!locationService.getLocationById(locationId).isPresent()) {
            return ResponseEntity.notFound().build();
        }
        locationService.deleteLocation(locationId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Add a parent to a location", description = "Add a parent relationship to a location.")
    @ApiResponse(responseCode = "200", description = "Parent relationship added successfully.")
    @EmitEvent(id = "LOCATION_PARENT_ADD", apiVersion = "1")
    @PostMapping("/{childId}/parents/{parentId}")
    public ResponseEntity<LocationParent> addParent(
            @Parameter(description = "ID of the child location", example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable UUID childId,
            @Parameter(description = "ID of the parent location", example = "018e1c9f-0000-7890-abcd-1234567890ab") @PathVariable UUID parentId,
            @Parameter(description = "Type of the parent relationship") @RequestParam ParentType parentType) {
        LocationParent parent = locationService.addParent(childId, parentId, parentType);
        return ResponseEntity.ok(parent);
    }

    @Operation(summary = "Get all location parents", description = "Retrieve all parent relationships for locations.")
    @ApiResponse(responseCode = "200", description = "List of location parents returned successfully.")
    @GetMapping("/parents")
    public List<LocationParent> getAllParents() {
        return locationService.getAllParents();
    }

    @Operation(summary = "Get all children for a location", description = "Retrieve all child locations for a given parent location.")
    @ApiResponse(responseCode = "200", description = "List of child locations returned successfully.")
    @GetMapping("/{locationId}/children")
    public List<Location> getAllChildren(
            @Parameter(description = "ID of the parent location", example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable UUID locationId,
            @Parameter(description = "Optional parent relationship type filter") @RequestParam(required = false) ParentType parentType) {
        return locationService.getAllChildren(locationId, parentType);
    }

    @Operation(summary = "Get responsible person for a location", description = "Retrieve the person responsible for a given location.")
    @ApiResponse(responseCode = "200", description = "Responsible person found and returned.")
    @ApiResponse(responseCode = "404", description = "Responsible person not found.")
    @GetMapping("/{locationId}/responsible-person")
    public ResponseEntity<PersonDTO> getResponsiblePerson(
            @Parameter(description = "ID of the location", example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable UUID locationId) {
        PersonDTO person = locationService.getResponsiblePerson(locationId);
        if (person == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(person);
    }
}
