package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.AssignStaffRequest;
import com.positivity.people.internal.dto.PersonLocationAssignmentDto;
import com.positivity.people.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/locations")
public class LocationController {

    private final LocationService locationService;

    public LocationController(@NonNull LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/{locationId}/staff")
    @Operation(summary = "List assignments for location")
    @ApiResponse(responseCode = "200", description = "Assignments returned")
    @ApiResponse(responseCode = "404", description = "Location not found")
    public ResponseEntity<List<PersonLocationAssignmentDto>> listStaff(@PathVariable UUID locationId) {
        return ResponseEntity.ok(locationService.getAssignmentsByLocation(locationId));
    }

    @PostMapping("/{locationId}/staff")
    @EmitEvent(id = "PEOPLE_STAFF_ASSIGNED", apiVersion = "1")
    @Operation(summary = "Assign staff to location")
    @ApiResponse(responseCode = "201", description = "Assignment created")
    @ApiResponse(responseCode = "404", description = "Location not found")
    @ApiResponse(responseCode = "409", description = "Assignment conflict")
    public ResponseEntity<PersonLocationAssignmentDto> assignStaff(
            @PathVariable UUID locationId,
            @Valid @RequestBody AssignStaffRequest request) {
        PersonLocationAssignmentDto assignment = locationService.assignStaff(locationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(assignment);
    }

    @DeleteMapping("/{locationId}/staff/{personId}")
    @EmitEvent(id = "PEOPLE_STAFF_UNASSIGNED", apiVersion = "1")
    @Operation(summary = "End staff assignment")
    @ApiResponse(responseCode = "204", description = "Assignment ended")
    @ApiResponse(responseCode = "404", description = "Location or assignment not found")
    public ResponseEntity<Void> unassignStaff(
            @PathVariable UUID locationId,
            @PathVariable UUID personId) {
        locationService.unassignStaff(locationId, personId);
        return ResponseEntity.noContent().build();
    }
}
