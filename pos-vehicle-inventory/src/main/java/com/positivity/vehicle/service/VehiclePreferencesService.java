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
     * Updates specific preference fields without replacing the entire map. A null
     * {@code serviceIntervalMonths} leaves the structured interval unchanged (use the upsert to
     * clear it); a legacy {@code serviceIntervalMonths} key inside {@code partialPreferences} is
     * promoted into the structured field instead of being stored in the blob (#1175).
     */
    VehicleCarePreference mergePreferences(
            UUID vehicleId,
            Map<String, Object> partialPreferences,
            Integer serviceIntervalMonths,
            UUID updatedByUserId);

    /**
     * Deletes preferences for a vehicle.
     */
    void deletePreferences(UUID vehicleId);
}
