package com.positivity.vehicle.internal.controller;

import com.positivity.events.EmitEvent;
import java.util.List;
import java.util.UUID;

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

import com.positivity.vehicle.internal.dao.VehicleDao;
import com.positivity.vehicle.internal.dto.VehicleLegacyMapper;
import com.positivity.vehicle.internal.dto.VehicleLegacyRequest;
import com.positivity.vehicle.internal.dto.VehicleLegacyResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Tag(name = "Vehicle API", description = "Endpoints for vehicle CRUD and VIN-based operations")
@RequiredArgsConstructor
@RestController
@PreAuthorize("isAuthenticated()")
@RequestMapping("/v1/vehicles-legacy")
public class VehicleController {
    private final VehicleDao vehicleDao;

    @Operation(summary = "Create a new vehicle", description = "Add a new vehicle to the inventory.")
    @ApiResponse(responseCode = "200", description = "Vehicle created successfully.")
    @EmitEvent(id = "VEHICLE_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<VehicleLegacyResponse> createVehicle(
            @Parameter(description = "Vehicle object to be created") @RequestBody VehicleLegacyRequest vehicle) {
        var saved = vehicleDao.save(VehicleLegacyMapper.toEntity(vehicle));
        log.info("Created legacy vehicle");
        return ResponseEntity.ok(VehicleLegacyMapper.toResponse(saved));
    }

    @Operation(summary = "Get vehicle by ID", description = "Retrieve a vehicle by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Vehicle found and returned.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @GetMapping("/{id}")
    public ResponseEntity<VehicleLegacyResponse> getVehicle(
            @Parameter(description = "ID of the vehicle to retrieve", example = "1") @PathVariable UUID id) {
        return vehicleDao.findById(id)
                .map(VehicleLegacyMapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get all vehicles", description = "Retrieve a list of all vehicles in the inventory.")
    @ApiResponse(responseCode = "200", description = "List of vehicles returned successfully.")
    @GetMapping
    public List<VehicleLegacyResponse> getAllVehicles() {
        return vehicleDao.findAll().stream()
                .map(VehicleLegacyMapper::toResponse)
                .toList();
    }

    @Operation(summary = "Update vehicle by ID", description = "Update an existing vehicle's details by its ID.")
    @ApiResponse(responseCode = "200", description = "Vehicle updated successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @EmitEvent(id = "VEHICLE_UPDATE", apiVersion = "1")
    @PutMapping("/{id}")
    public ResponseEntity<VehicleLegacyResponse> updateVehicle(
            @Parameter(description = "ID of the vehicle to update", example = "1") @PathVariable UUID id,
            @Parameter(description = "Updated vehicle object") @RequestBody VehicleLegacyRequest updated) {
        var existing = vehicleDao.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var vehicle = existing.get();
        VehicleLegacyMapper.applyCoreFields(vehicle, updated);
        var saved = vehicleDao.save(vehicle);
        log.info("Updated legacy vehicle with id {}", id);
        return ResponseEntity.ok(VehicleLegacyMapper.toResponse(saved));
    }

    @Operation(summary = "Delete vehicle by ID", description = "Delete a vehicle from the inventory by its ID.")
    @ApiResponse(responseCode = "204", description = "Vehicle deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @EmitEvent(id = "VEHICLE_DELETE", apiVersion = "1")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVehicle(
            @Parameter(description = "ID of the vehicle to delete", example = "1") @PathVariable UUID id) {
        if (vehicleDao.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        vehicleDao.deleteById(id);
        log.info("Deleted vehicle with id {}", id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Create vehicle by VIN", description = "Add a new vehicle to the inventory using its VIN.")
    @ApiResponse(responseCode = "200", description = "Vehicle created successfully.")
    @EmitEvent(id = "VEHICLE_CREATE", apiVersion = "1")
    @PostMapping("/vin")
    public ResponseEntity<VehicleLegacyResponse> createVehicleByVIN(
            @Parameter(description = "Vehicle object to be created") @RequestBody VehicleLegacyRequest vehicle) {
        if (vehicle.getVin() == null || vehicle.getVin().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        var saved = vehicleDao.save(VehicleLegacyMapper.toEntity(vehicle));
        log.info("Created legacy vehicle with VIN {}", vehicle.getVin());
        return ResponseEntity.ok(VehicleLegacyMapper.toResponse(saved));
    }

    @Operation(summary = "Get vehicle by VIN", description = "Retrieve a vehicle by its VIN.")
    @ApiResponse(responseCode = "200", description = "Vehicle found and returned.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @GetMapping("/vin/{vin}")
    public ResponseEntity<VehicleLegacyResponse> getVehicleByVIN(
            @Parameter(description = "VIN of the vehicle to retrieve", example = "1HGCM82633A004352") @PathVariable String vin) {
        return vehicleDao.findByVIN(vin)
                .map(VehicleLegacyMapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Update vehicle by VIN", description = "Update an existing vehicle's details by its VIN.")
    @ApiResponse(responseCode = "200", description = "Vehicle updated successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @EmitEvent(id = "VEHICLE_UPDATE", apiVersion = "1")
    @PutMapping("/vin/{vin}")
    public ResponseEntity<VehicleLegacyResponse> updateVehicleByVIN(
            @Parameter(description = "VIN of the vehicle to update", example = "1HGCM82633A004352") @PathVariable String vin,
            @Parameter(description = "Updated vehicle object") @RequestBody VehicleLegacyRequest updated) {
        var existing = vehicleDao.findByVIN(vin);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var vehicle = existing.get();
        VehicleLegacyMapper.applyCoreFields(vehicle, updated);
        var saved = vehicleDao.save(vehicle);
        log.info("Updated legacy vehicle with VIN {}", vin);
        return ResponseEntity.ok(VehicleLegacyMapper.toResponse(saved));
    }

    @Operation(summary = "Delete vehicle by VIN", description = "Delete a vehicle from the inventory by its VIN.")
    @ApiResponse(responseCode = "204", description = "Vehicle deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @EmitEvent(id = "VEHICLE_DELETE", apiVersion = "1")
    @DeleteMapping("/vin/{vin}")
    public ResponseEntity<Void> deleteVehicleByVIN(
            @Parameter(description = "VIN of the vehicle to delete", example = "1HGCM82633A004352") @PathVariable String vin) {
        if (vehicleDao.findByVIN(vin).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        vehicleDao.deleteByVIN(vin);
        log.info("Deleted vehicle with VIN {}", vin);
        return ResponseEntity.noContent().build();
    }
}
