package com.positivity.vehicle.internal.service;

import com.positivity.vehicle.internal.dto.UpsertPreferencesRequest;
import com.positivity.vehicle.internal.dto.VehicleCarePreferenceResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface VehiclePreferencesService {

    /**
     * Gets preferences for a vehicle. Returns empty Optional if not found.
     */
    @NonNull
    Optional<VehicleCarePreferenceResponse> getPreferences(@NonNull UUID vehicleId);

    /**
     * Creates or updates preferences for a vehicle (upsert operation).
     */
    @NonNull
    VehicleCarePreferenceResponse upsertPreferences(@NonNull UpsertPreferencesRequest request);

    /**
     * Updates specific preference fields without replacing the entire map. A null
     * {@code serviceIntervalMonths} leaves the structured interval unchanged (use the upsert to
     * clear it); a legacy {@code serviceIntervalMonths} key inside {@code partialPreferences} is
     * promoted into the structured field instead of being stored in the blob (#1175).
     */
    @NonNull
    VehicleCarePreferenceResponse mergePreferences(
            @NonNull UUID vehicleId,
            @NonNull Map<String, Object> partialPreferences,
            Integer serviceIntervalMonths,
            UUID updatedByUserId);

    /**
     * Deletes preferences for a vehicle.
     */
    void deletePreferences(@NonNull UUID vehicleId);
}
