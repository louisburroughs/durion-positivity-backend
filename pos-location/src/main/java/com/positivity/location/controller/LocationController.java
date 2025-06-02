package com.positivity.location.controller;

import com.positivity.location.entity.Location;
import com.positivity.location.entity.LocationParent;
import com.positivity.location.entity.ParentType;
import com.positivity.location.dto.PersonDTO;
import com.positivity.location.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "Location API", description = "Operations related to locations and their relationships")
@RestController
@RequestMapping("/api/locations")
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
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Location found and returned."),
            @ApiResponse(responseCode = "404", description = "Location not found.")
    })
    @GetMapping("/{id}")
    public ResponseEntity<Location> getLocationById(
            @Parameter(description = "ID of the location to retrieve", example = "1")
            @PathVariable Long id) {
        return locationService.getLocationById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new location", description = "Add a new location to the system.")
    @ApiResponse(responseCode = "200", description = "Location created successfully.")
    @PostMapping
    public ResponseEntity<Location> createLocation(
            @Parameter(description = "Location object to be created")
            @RequestBody Location location) {
        Location saved = locationService.saveLocation(location);
        return ResponseEntity.ok(saved);
    }

    @Operation(summary = "Update an existing location", description = "Update the details of an existing location.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Location updated successfully."),
            @ApiResponse(responseCode = "404", description = "Location not found.")
    })
    @PutMapping("/{id}")
    public ResponseEntity<Location> updateLocation(
            @Parameter(description = "ID of the location to update", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Updated location object")
            @RequestBody Location location) {
        if (!locationService.getLocationById(id).isPresent()) {
            return ResponseEntity.notFound().build();
        }
        location.setId(id);
        Location updated = locationService.saveLocation(location);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Delete a location", description = "Delete a location by its unique ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Location deleted successfully."),
            @ApiResponse(responseCode = "404", description = "Location not found.")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLocation(
            @Parameter(description = "ID of the location to delete", example = "1")
            @PathVariable Long id) {
        if (!locationService.getLocationById(id).isPresent()) {
            return ResponseEntity.notFound().build();
        }
        locationService.deleteLocation(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Add a parent to a location", description = "Add a parent relationship to a location.")
    @ApiResponse(responseCode = "200", description = "Parent relationship added successfully.")
    @PostMapping("/{childId}/parents/{parentId}")
    public ResponseEntity<LocationParent> addParent(
            @Parameter(description = "ID of the child location", example = "2")
            @PathVariable Long childId,
            @Parameter(description = "ID of the parent location", example = "1")
            @PathVariable Long parentId,
            @Parameter(description = "Type of the parent relationship")
            @RequestParam ParentType parentType) {
        LocationParent parent = locationService.addParent(childId, parentId, parentType);
        return ResponseEntity.ok(parent);
    }

    @Operation(summary = "Get all location parents", description = "Retrieve all parent relationships for locations.")
    @ApiResponse(responseCode = "200", description = "List of location parents returned successfully.")
    @GetMapping("/parents")
    public List<LocationParent> getAllParents() {
        return locationService.getAllParents();
    }

    @Operation(summary = "Get responsible person for a location", description = "Retrieve the person responsible for a given location.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Responsible person found and returned."),
            @ApiResponse(responseCode = "404", description = "Responsible person not found.")
    })
    @GetMapping("/{id}/responsible-person")
    public ResponseEntity<PersonDTO> getResponsiblePerson(
            @Parameter(description = "ID of the location", example = "1")
            @PathVariable Long id) {
        PersonDTO person = locationService.getResponsiblePerson(id);
        if (person == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(person);
    }
}
