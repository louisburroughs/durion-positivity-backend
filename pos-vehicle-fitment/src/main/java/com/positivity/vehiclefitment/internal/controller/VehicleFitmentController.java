package com.positivity.vehiclefitment.internal.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.vehiclefitment.internal.entity.Make;
import com.positivity.vehiclefitment.internal.entity.Manufacturer;
import com.positivity.vehiclefitment.internal.entity.Model;
import com.positivity.vehiclefitment.internal.entity.VehicleType;
import com.positivity.vehiclefitment.service.VehicleFitmentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Tag(name = "Vehicle Fitment API", description = "Endpoints for vehicle manufacturers, makes, models, and types")
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/vehicle-fitment")
public class VehicleFitmentController {

    private final VehicleFitmentService vehicleFitmentService;

    @Operation(summary = "Get all manufacturers", description = "Retrieve a list of all vehicle manufacturers.")
    @ApiResponse(responseCode = "200", description = "List of manufacturers returned successfully.")
    @GetMapping("/manufacturers")
    public List<Manufacturer> getManufacturers() {
        return vehicleFitmentService.getManufacturers();
    }

    @Operation(summary = "Get makes by manufacturer", description = "Retrieve all makes for a given manufacturer.")
    @ApiResponse(responseCode = "200", description = "List of makes returned successfully.")
    @GetMapping("/makes/{manufacturerId}")
    public List<Make> getMakesByManufacturer(
            @Parameter(description = "ID of the manufacturer", example = "1") @PathVariable UUID manufacturerId) {
        return vehicleFitmentService.getMakesByManufacturer(manufacturerId);
    }

    @Operation(summary = "Get models by make", description = "Retrieve all models for a given make.")
    @ApiResponse(responseCode = "200", description = "List of models returned successfully.")
    @GetMapping("/models/{makeId}")
    public List<Model> getModelsByMake(
            @Parameter(description = "ID of the make", example = "1") @PathVariable UUID makeId) {
        return vehicleFitmentService.getModelsByMake(makeId);
    }

    @Operation(summary = "Get vehicle types for make", description = "Retrieve all vehicle types for a given make.")
    @ApiResponse(responseCode = "200", description = "List of vehicle types returned successfully.")
    @GetMapping("/vehicle-types/{makeId}")
    public List<VehicleType> getVehicleTypesForMake(
            @Parameter(description = "ID of the make", example = "1") @PathVariable UUID makeId) {
        return vehicleFitmentService.getVehicleTypesForMake(makeId);
    }
}
