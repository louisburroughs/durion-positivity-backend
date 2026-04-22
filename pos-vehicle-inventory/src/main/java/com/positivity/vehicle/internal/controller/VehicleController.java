package com.positivity.vehicle.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.vehicle.internal.dto.VehicleLegacyRequest;
import com.positivity.vehicle.internal.dto.VehicleLegacyResponse;
import com.positivity.vehicle.service.VehicleLegacyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Tag(name = "Vehicle API", description = "Endpoints for vehicle CRUD and VIN-based operations")
@RequiredArgsConstructor
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@PreAuthorize("isAuthenticated()")
@RequestMapping("/v1/vehicles-legacy")
public class VehicleController {
    private final VehicleLegacyService vehicleLegacyService;

    @Operation(summary = "Create a new vehicle", description = "Add a new vehicle to the inventory.")
    @ApiResponse(responseCode = "200", description = "Vehicle created successfully.")
    @EmitEvent(id = "VEHICLE_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<VehicleLegacyResponse> createVehicle(
            @Parameter(description = "Vehicle object to be created") @RequestBody VehicleLegacyRequest vehicle) {
        try {
            return ResponseEntity.ok(vehicleLegacyService.createVehicle(vehicle));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid create vehicle request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Get vehicle by ID", description = "Retrieve a vehicle by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Vehicle found and returned.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @GetMapping("/{id}")
    public ResponseEntity<VehicleLegacyResponse> getVehicle(
            @Parameter(description = "ID of the vehicle to retrieve", example = "1") @PathVariable UUID id) {
        return vehicleLegacyService
                .getVehicle(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get all vehicles", description = "Retrieve a list of all vehicles in the inventory.")
    @ApiResponse(responseCode = "200", description = "List of vehicles returned successfully.")
    @GetMapping
    public List<VehicleLegacyResponse> getAllVehicles() {
        return vehicleLegacyService.getAllVehicles();
    }

    @Operation(summary = "Update vehicle by ID", description = "Update an existing vehicle's details by its ID.")
    @ApiResponse(responseCode = "200", description = "Vehicle updated successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @EmitEvent(id = "VEHICLE_UPDATE", apiVersion = "1")
    @PutMapping("/{id}")
    public ResponseEntity<VehicleLegacyResponse> updateVehicle(
            @Parameter(description = "ID of the vehicle to update", example = "1") @PathVariable UUID id,
            @Parameter(description = "Updated vehicle object") @RequestBody VehicleLegacyRequest updated) {
        try {
            return vehicleLegacyService
                    .updateVehicle(id, updated)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid update request for vehicle id {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete vehicle by ID", description = "Delete a vehicle from the inventory by its ID.")
    @ApiResponse(responseCode = "204", description = "Vehicle deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @EmitEvent(id = "VEHICLE_DELETE", apiVersion = "1")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVehicle(
            @Parameter(description = "ID of the vehicle to delete", example = "1") @PathVariable UUID id) {
        try {
            if (!vehicleLegacyService.deleteVehicle(id)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid delete request for vehicle id {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Create vehicle by VIN", description = "Add a new vehicle to the inventory using its VIN.")
    @ApiResponse(responseCode = "200", description = "Vehicle created successfully.")
    @EmitEvent(id = "VEHICLE_CREATE", apiVersion = "1")
    @PostMapping("/vin")
    public ResponseEntity<VehicleLegacyResponse> createVehicleByVIN(
            @Parameter(description = "Vehicle object to be created") @RequestBody VehicleLegacyRequest vehicle) {
        try {
            return ResponseEntity.ok(vehicleLegacyService.createVehicleByVin(vehicle));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid create-by-vin request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Get vehicle by VIN", description = "Retrieve a vehicle by its VIN.")
    @ApiResponse(responseCode = "200", description = "Vehicle found and returned.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @GetMapping("/vin/{vin}")
    public ResponseEntity<VehicleLegacyResponse> getVehicleByVIN(
            @Parameter(description = "VIN of the vehicle to retrieve", example = "1HGCM82633A004352") @PathVariable String vin) {
        try {
            return vehicleLegacyService
                    .getVehicleByVin(vin)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid VIN lookup request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Update vehicle by VIN", description = "Update an existing vehicle's details by its VIN.")
    @ApiResponse(responseCode = "200", description = "Vehicle updated successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @EmitEvent(id = "VEHICLE_UPDATE", apiVersion = "1")
    @PutMapping("/vin/{vin}")
    public ResponseEntity<VehicleLegacyResponse> updateVehicleByVIN(
            @Parameter(description = "VIN of the vehicle to update", example = "1HGCM82633A004352") @PathVariable String vin,
            @Parameter(description = "Updated vehicle object") @RequestBody VehicleLegacyRequest updated) {
        try {
            return vehicleLegacyService
                    .updateVehicleByVin(vin, updated)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid update-by-vin request for VIN {}: {}", vin, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete vehicle by VIN", description = "Delete a vehicle from the inventory by its VIN.")
    @ApiResponse(responseCode = "204", description = "Vehicle deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @EmitEvent(id = "VEHICLE_DELETE", apiVersion = "1")
    @DeleteMapping("/vin/{vin}")
    public ResponseEntity<Void> deleteVehicleByVIN(
            @Parameter(description = "VIN of the vehicle to delete", example = "1HGCM82633A004352") @PathVariable String vin) {
        try {
            if (!vehicleLegacyService.deleteVehicleByVin(vin)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid delete-by-vin request for VIN {}: {}", vin, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}
