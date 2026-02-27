package com.positivity.vehicle.service;

import java.util.Optional;
import java.util.UUID;

import com.positivity.shared.dto.CreateVehicleRequest;
import com.positivity.shared.dto.VehicleResponse;

public interface VehicleService {

    /**
     * Creates a new vehicle with VIN validation and normalization.
     */
    VehicleResponse createVehicle(CreateVehicleRequest request);

    /**
     * Gets a vehicle by ID.
     */
    Optional<VehicleResponse> getVehicle(UUID vehicleId);

    /**
     * Gets a vehicle by VIN (normalized lookup).
     */
    Optional<VehicleResponse> getVehicleByVin(String vin);

    /**
     * Updates a vehicle.
     */
    VehicleResponse updateVehicle(UUID vehicleId, CreateVehicleRequest request);

    /**
     * Deletes (deactivates) a vehicle.
     */
    void deleteVehicle(UUID vehicleId);

}