package com.positivity.vehicle.service;

import com.positivity.vehicle.internal.dto.VehicleLegacyRequest;
import com.positivity.vehicle.internal.dto.VehicleLegacyResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service API for legacy vehicle CRUD and VIN-based operations.
 */
public interface VehicleLegacyService {

    @NonNull
    VehicleLegacyResponse createVehicle(@NonNull VehicleLegacyRequest vehicle);

    Optional<VehicleLegacyResponse> getVehicle(@NonNull UUID id);

    @NonNull
    List<VehicleLegacyResponse> getAllVehicles();

    Optional<VehicleLegacyResponse> updateVehicle(@NonNull UUID id, @NonNull VehicleLegacyRequest updated);

    boolean deleteVehicle(@NonNull UUID id);

    @NonNull
    VehicleLegacyResponse createVehicleByVin(@NonNull VehicleLegacyRequest vehicle);

    Optional<VehicleLegacyResponse> getVehicleByVin(@NonNull String vin);

    Optional<VehicleLegacyResponse> updateVehicleByVin(@NonNull String vin, @NonNull VehicleLegacyRequest updated);

    boolean deleteVehicleByVin(@NonNull String vin);
}
