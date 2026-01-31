package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Mobile Unit API", description = "Operations for managing mobile units within locations")
@RestController
@RequestMapping("/v1/locations")
@RequiredArgsConstructor
public class MobileUnitController {

    @Operation(summary = "Get mobile units", description = "List all mobile units or get a specific mobile unit detail by locationId and bayId.")
    @ApiResponse(responseCode = "200", description = "Mobile units retrieved successfully.")
    @GetMapping({ "/mobileUnit", "/{locationId}/mobileUnit/{bayId}" })
    public ResponseEntity<Object> getMobileUnits(
            @Parameter(description = "Location ID (optional for specific mobile unit)") @PathVariable(required = false) Long locationId,
            @Parameter(description = "Bay ID (optional for specific mobile unit)") @PathVariable(required = false) Long bayId) {
        log.info("Fetching mobile units - locationId={}, bayId={}", locationId, bayId);
        // TODO: Implement mobile unit retrieval logic
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Create mobile unit", description = "Create a new mobile unit for a specific location. Validate baseLocationId and capabilities.")
    @ApiResponse(responseCode = "200", description = "Mobile unit created successfully.")
    @EmitEvent(id = "LOCATION_MOBILE_UNIT_CREATE", apiVersion = "1")
    @PostMapping("/{locationId}/mobileUnit")
    public ResponseEntity<Object> createMobileUnit(
            @Parameter(description = "Location ID", example = "1") @PathVariable Long locationId,
            @Parameter(description = "Mobile unit creation request body") @RequestBody(required = false) Object request) {
        log.info("Creating mobile unit for location ID: {}", locationId);
        // TODO: Implement mobile unit creation logic with validation
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Manage mobile units", description = "Create or update mobile units in bulk.")
    @ApiResponse(responseCode = "200", description = "Mobile units managed successfully.")
    @EmitEvent(id = "LOCATION_MOBILE_UNIT_MANAGE", apiVersion = "1")
    @PutMapping("/mobileUnit")
    public ResponseEntity<Object> manageMobileUnits(
            @Parameter(description = "Mobile unit management request body") @RequestBody(required = false) Object request) {
        log.info("Managing mobile units");
        // TODO: Implement mobile unit management logic
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Delete mobile unit", description = "Delete a specific mobile unit by locationId and bayId.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Mobile unit deleted successfully."),
            @ApiResponse(responseCode = "404", description = "Mobile unit not found.")
    })
    @DeleteMapping("/{locationId}/mobileUnit/{bayId}")
    public ResponseEntity<Void> deleteMobileUnit(
            @Parameter(description = "Location ID", example = "1") @PathVariable Long locationId,
            @Parameter(description = "Bay ID", example = "1") @PathVariable Long bayId) {
        log.info("Deleting mobile unit ID: {} for location ID: {}", bayId, locationId);
        // TODO: Implement mobile unit deletion logic
        return ResponseEntity.noContent().build();
    }
}
