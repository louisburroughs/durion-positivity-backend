package com.positivity.vehicle.controller;

import com.positivity.vehicle.dao.VehicleDao;
import com.positivity.vehicle.model.VehicleEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@Slf4j
@Tag(name = "Vehicle API", description = "Endpoints for vehicle CRUD and VIN-based operations")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {
    private final VehicleDao vehicleDao;

    @Operation(summary = "Create a new vehicle", description = "Add a new vehicle to the inventory.")
    @ApiResponse(responseCode = "200", description = "Vehicle created successfully.")
    @PostMapping
    public ResponseEntity<VehicleEntity> createVehicle(
            @Parameter(description = "Vehicle object to be created") @RequestBody VehicleEntity vehicle) {
        VehicleEntity saved = vehicleDao.save(vehicle);
        log.info("Created vehicle with id {}", saved.getId());
        return ResponseEntity.ok(saved);
    }

    @Operation(summary = "Get vehicle by ID", description = "Retrieve a vehicle by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Vehicle found and returned.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @GetMapping("/{id}")
    public ResponseEntity<VehicleEntity> getVehicle(
            @Parameter(description = "ID of the vehicle to retrieve", example = "1") @PathVariable Long id) {
        Optional<VehicleEntity> vehicle = vehicleDao.findById(id);
        return vehicle.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get all vehicles", description = "Retrieve a list of all vehicles in the inventory.")
    @ApiResponse(responseCode = "200", description = "List of vehicles returned successfully.")
    @GetMapping
    public List<VehicleEntity> getAllVehicles() {
        return vehicleDao.findAll();
    }

    @Operation(summary = "Update vehicle by ID", description = "Update an existing vehicle's details by its ID.")
    @ApiResponse(responseCode = "200", description = "Vehicle updated successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @PutMapping("/{id}")
    public ResponseEntity<VehicleEntity> updateVehicle(
            @Parameter(description = "ID of the vehicle to update", example = "1") @PathVariable Long id,
            @Parameter(description = "Updated vehicle object") @RequestBody VehicleEntity updated) {
        Optional<VehicleEntity> existing = vehicleDao.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        VehicleEntity vehicle = existing.get();
        vehicle.setMake(updated.getMake());
        vehicle.setModel(updated.getModel());
        vehicle.setYear(updated.getYear());
        VehicleEntity saved = vehicleDao.save(vehicle);
        log.info("Updated vehicle with id {}", saved.getId());
        return ResponseEntity.ok(saved);
    }

    @Operation(summary = "Delete vehicle by ID", description = "Delete a vehicle from the inventory by its ID.")
    @ApiResponse(responseCode = "204", description = "Vehicle deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVehicle(
            @Parameter(description = "ID of the vehicle to delete", example = "1") @PathVariable Long id) {
        if (vehicleDao.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        vehicleDao.deleteById(id);
        log.info("Deleted vehicle with id {}", id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Create vehicle by VIN", description = "Add a new vehicle to the inventory using its VIN.")
    @ApiResponse(responseCode = "200", description = "Vehicle created successfully.")
    @PostMapping("/vin/{vin}")
    public ResponseEntity<VehicleEntity> createVehicleByVIN(
            @Parameter(description = "VIN of the vehicle to create", example = "1HGCM82633A004352") @PathVariable String vin,
            @Parameter(description = "Vehicle object to be created") @RequestBody VehicleEntity vehicle) {
        vehicle.setVIN(vin);
        VehicleEntity saved = vehicleDao.save(vehicle);
        log.info("Created vehicle with VIN {}", saved.getVIN());
        return ResponseEntity.ok(saved);
    }

    @Operation(summary = "Get vehicle by VIN", description = "Retrieve a vehicle by its VIN.")
    @ApiResponse(responseCode = "200", description = "Vehicle found and returned.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @GetMapping("/vin/{vin}")
    public ResponseEntity<VehicleEntity> getVehicleByVIN(
            @Parameter(description = "VIN of the vehicle to retrieve", example = "1HGCM82633A004352") @PathVariable String vin) {
        Optional<VehicleEntity> vehicle = vehicleDao.findByVIN(vin);
        return vehicle.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Update vehicle by VIN", description = "Update an existing vehicle's details by its VIN.")
    @ApiResponse(responseCode = "200", description = "Vehicle updated successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @PutMapping("/vin/{vin}")
    public ResponseEntity<VehicleEntity> updateVehicleByVIN(
            @Parameter(description = "VIN of the vehicle to update", example = "1HGCM82633A004352") @PathVariable String vin,
            @Parameter(description = "Updated vehicle object") @RequestBody VehicleEntity updated) {
        Optional<VehicleEntity> existing = vehicleDao.findByVIN(vin);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        VehicleEntity vehicle = existing.get();
        vehicle.setMake(updated.getMake());
        vehicle.setModel(updated.getModel());
        vehicle.setYear(updated.getYear());
        vehicle.setVIN(vin);
        VehicleEntity saved = vehicleDao.save(vehicle);
        log.info("Updated vehicle with VIN {}", saved.getVIN());
        return ResponseEntity.ok(saved);
    }

    @Operation(summary = "Delete vehicle by VIN", description = "Delete a vehicle from the inventory by its VIN.")
    @ApiResponse(responseCode = "204", description = "Vehicle deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
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
