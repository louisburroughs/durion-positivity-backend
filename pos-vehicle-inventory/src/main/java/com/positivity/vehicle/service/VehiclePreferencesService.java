package com.positivity.vehicle.service;

import com.positivity.vehicle.internal.dto.UpsertPreferencesRequest;
import com.positivity.vehicle.internal.entity.VehicleCarePreference;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface VehiclePreferencesService {

    /**
     * Gets preferences for a vehicle. Returns empty Optional if not found.
     */
    Optional<VehicleCarePreference> getPreferences(UUID vehicleId);

    /**
     * Creates or updates preferences for a vehicle (upsert operation).
     */
    VehicleCarePreference upsertPreferences(UpsertPreferencesRequest request);

    /**
     * Updates specific preference fields without replacing the entire map.
     */
    VehicleCarePreference mergePreferences(
            UUID vehicleId, Map<String, Object> partialPreferences, UUID updatedByUserId);

    /**
     * Deletes preferences for a vehicle.
     */
    void deletePreferences(UUID vehicleId);
}
