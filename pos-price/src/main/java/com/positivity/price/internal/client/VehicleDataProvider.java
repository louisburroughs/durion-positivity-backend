package com.positivity.price.internal.client;

import java.util.Optional;
import java.util.UUID;

public interface VehicleDataProvider {
    Optional<VehicleContext> getVehicleContext(UUID vehicleId);
}
