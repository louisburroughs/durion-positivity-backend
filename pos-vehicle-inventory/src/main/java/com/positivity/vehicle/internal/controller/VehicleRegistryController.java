package com.positivity.vehicle.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.dto.CreateVehicleRequest;
import com.positivity.shared.dto.UpdateVehicleRequest;
import com.positivity.shared.dto.VehicleResponse;
import com.positivity.vehicle.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * REST controller for vehicle registry CRUD operations.
 */
@Slf4j
@Tag(name = "Vehicle Registry API", description = "Operations for creating and maintaining vehicle registry records")
@RequiredArgsConstructor
@RestController
@PreAuthorize("isAuthenticated()")
@RequestMapping("/v1/vehicle-registry")
public class VehicleRegistryController {

    private final VehicleService vehicleService;

    @Operation(summary = "Create vehicle", description = "Create a new vehicle registry record")
    @ApiResponse(responseCode = "201", description = "Vehicle created successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid vehicle request.")
    @EmitEvent(id = "VEHICLE_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<VehicleResponse> createVehicle(
            @Parameter(description = "Vehicle creation request", required = true) @RequestBody CreateVehicleRequest request) {
        try {
            VehicleResponse response = vehicleService.createVehicle(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create vehicle: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Get vehicle by ID", description = "Retrieve a vehicle by its unique ID")
    @ApiResponse(responseCode = "200", description = "Vehicle retrieved successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @GetMapping("/{vehicleId}")
    public ResponseEntity<VehicleResponse> getVehicle(
            @Parameter(description = "Vehicle UUID", required = true) @PathVariable UUID vehicleId) {
        return vehicleService.getVehicle(vehicleId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get vehicle by VIN", description = "Retrieve a vehicle by VIN")
    @ApiResponse(responseCode = "200", description = "Vehicle retrieved successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @GetMapping("/vin/{vin}")
    public ResponseEntity<VehicleResponse> getVehicleByVin(
            @Parameter(description = "Vehicle VIN", required = true) @PathVariable String vin) {
        return vehicleService.getVehicleByVin(vin)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Update vehicle", description = "Update an existing vehicle by ID")
    @ApiResponse(responseCode = "200", description = "Vehicle updated successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid vehicle request.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @EmitEvent(id = "VEHICLE_UPDATE", apiVersion = "1")
    @PutMapping("/{vehicleId}")
    public ResponseEntity<VehicleResponse> updateVehicle(
            @Parameter(description = "Vehicle UUID", required = true) @PathVariable UUID vehicleId,
            @Parameter(description = "Vehicle update request", required = true) @RequestBody UpdateVehicleRequest request) {
        try {
            VehicleResponse response = vehicleService.updateVehicle(vehicleId, request);
            return ResponseEntity.ok(response);
        } catch (EntityNotFoundException e) {
            log.warn("Vehicle not found for update {}: {}", vehicleId, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update vehicle {}: {}", vehicleId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete vehicle", description = "Deactivate a vehicle by ID")
    @ApiResponse(responseCode = "204", description = "Vehicle deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @EmitEvent(id = "VEHICLE_DELETE", apiVersion = "1")
    @DeleteMapping("/{vehicleId}")
    public ResponseEntity<Void> deleteVehicle(
            @Parameter(description = "Vehicle UUID", required = true) @PathVariable UUID vehicleId) {
        try {
            vehicleService.deleteVehicle(vehicleId);
            return ResponseEntity.noContent().build();
        } catch (EntityNotFoundException e) {
            log.warn("Vehicle not found for delete {}: {}", vehicleId, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid delete request for vehicle {}: {}", vehicleId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}
