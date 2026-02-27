package com.positivity.shopmanager.internal.adapter;

import java.util.Map;
import java.util.UUID;

public interface VehicleAdapter {
    Map<String, Object> getVehicleById(UUID vehicleId);
}
