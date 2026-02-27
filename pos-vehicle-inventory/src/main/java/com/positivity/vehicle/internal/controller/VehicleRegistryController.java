package com.positivity.vehicle.internal.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.events.EmitEvent;
import com.positivity.shared.dto.CreateVehicleRequest;
import com.positivity.shared.dto.VehicleResponse;
import com.positivity.vehicle.service.VehicleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for vehicle CRUD operations - CAP:091 Story #105.
 */
@Slf4j
@Tag(name = "Vehicle Registry", description = "Vehicle CRUD and management operations for CAP:091")
@RequiredArgsConstructor
@RestController
@PreAuthorize("isAuthenticated()")
@RequestMapping("/v1/vehicles")
public class VehicleRegistryController {

    private final VehicleService vehicleService;

    @Operation(summary = "Create a new vehicle", description = "Creates a new vehicle record with VIN validation and normalization. "
            +
            "VIN must be globally unique across all active vehicles. " +
            "Story #105 / Issue #105.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Vehicle created successfully", content = @Content(schema = @Schema(implementation = VehicleResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request - VIN format invalid, missing required fields, or duplicate VIN", content = @Content),
            @ApiResponse(responseCode = "409", description = "Conflict - VIN already exists for another active vehicle", content = @Content)
    })
    @PostMapping
    @EmitEvent(id = "VEHICLE_CREATE", apiVersion = "1")
    public ResponseEntity<VehicleResponse> createVehicle(
            @Parameter(description = "Vehicle creation request with VIN, unit number, and description", required = true) @RequestBody CreateVehicleRequest request) {

        try {
            VehicleResponse response = vehicleService.createVehicle(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create vehicle: {}", e.getMessage());
            if (e.getMessage().contains("already exists")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Get vehicle by ID", description = "Retrieves a vehicle by its unique ID. Story #105.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Vehicle found", content = @Content(schema = @Schema(implementation = VehicleResponse.class))),
            @ApiResponse(responseCode = "404", description = "Vehicle not found", content = @Content)
    })
    @GetMapping("/{vehicleId}")
    public ResponseEntity<VehicleResponse> getVehicle(
            @Parameter(description = "Vehicle UUID", required = true) @PathVariable UUID vehicleId) {

        return vehicleService.getVehicle(vehicleId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get vehicle by VIN", description = "Retrieves a vehicle by its VIN (normalized lookup). Story #105.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Vehicle found", content = @Content(schema = @Schema(implementation = VehicleResponse.class))),
            @ApiResponse(responseCode = "404", description = "Vehicle not found", content = @Content)
    })
    @GetMapping("/vin/{vin}")
    public ResponseEntity<VehicleResponse> getVehicleByVin(
            @Parameter(description = "Vehicle VIN (17 characters)", required = true, example = "1HGCM82633A004352") @PathVariable String vin) {

        return vehicleService.getVehicleByVin(vin)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Update vehicle", description = "Updates an existing vehicle's details. VIN cannot be changed. Story #105.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Vehicle updated successfully", content = @Content(schema = @Schema(implementation = VehicleResponse.class))),
            @ApiResponse(responseCode = "404", description = "Vehicle not found", content = @Content),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content)
    })
    @PutMapping("/{vehicleId}")
    @EmitEvent(id = "VEHICLE_UPDATE", apiVersion = "1")
    public ResponseEntity<VehicleResponse> updateVehicle(
            @Parameter(description = "Vehicle UUID", required = true) @PathVariable UUID vehicleId,
            @Parameter(description = "Vehicle update request", required = true) @RequestBody CreateVehicleRequest request) {

        try {
            VehicleResponse response = vehicleService.updateVehicle(vehicleId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update vehicle {}: {}", vehicleId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Delete vehicle", description = "Soft-deletes (deactivates) a vehicle. Story #105.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Vehicle deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Vehicle not found")
    })
    @DeleteMapping("/{vehicleId}")
    @EmitEvent(id = "VEHICLE_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteVehicle(
            @Parameter(description = "Vehicle UUID", required = true) @PathVariable UUID vehicleId) {

        try {
            vehicleService.deleteVehicle(vehicleId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("Failed to delete vehicle {}: {}", vehicleId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
