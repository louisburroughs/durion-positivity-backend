package com.positivity.vehicle.internal.service;

import com.positivity.vehicle.internal.entity.VehicleCarePreference;
import com.positivity.vehicle.internal.repository.VehicleCarePreferenceRepository;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing vehicle care preferences (CAP:091 Story #102).
 * Handles flexible JSONB preference storage with validation.
 * PUBLIC API for preferences management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehiclePreferencesService {

    private final VehicleCarePreferenceRepository preferencesRepository;
    private final VehicleRecordRepository vehicleRepository;

    /**
     * Gets preferences for a vehicle. Returns empty Optional if not found.
     */
    @Transactional(readOnly = true)
    public Optional<VehicleCarePreference> getPreferences(@NonNull UUID vehicleId) {
        log.debug("Fetching preferences for vehicleId={}", vehicleId);
        return preferencesRepository.findByVehicleId(vehicleId);
    }

    /**
     * Creates or updates preferences for a vehicle (upsert operation).
     */
    @Transactional
    public VehicleCarePreference upsertPreferences(@NonNull UpsertPreferencesRequest request) {
        log.info("Upserting preferences for vehicleId={}", request.vehicleId());

        // Validate vehicle exists
        if (!vehicleRepository.existsById(request.vehicleId())) {
            throw new EntityNotFoundException("Vehicle not found: " + request.vehicleId());
        }

        // Validate preferences map is not null (can be empty)
        if (request.preferences() == null) {
            throw new IllegalArgumentException("Preferences map cannot be null (use empty map instead)");
        }

        var existing = preferencesRepository.findByVehicleId(request.vehicleId());

        VehicleCarePreference preference;
        if (existing.isPresent()) {
            // Update existing
            preference = existing.get();
            preference.setPreferences(request.preferences());
            if (request.serviceNotes() != null) {
                preference.setServiceNotes(request.serviceNotes());
            }
            if (request.updatedByUserId() != null) {
                preference.setUpdatedByUserId(request.updatedByUserId());
            }
            log.info("Updating existing preferences: id={}", preference.getId());
        } else {
            // Create new
            preference = VehicleCarePreference.builder()
                    .vehicleId(request.vehicleId())
                    .preferences(request.preferences())
                    .serviceNotes(request.serviceNotes())
                    .createdByUserId(request.createdByUserId())
                    .updatedByUserId(request.updatedByUserId())
                    .build();
            log.info("Creating new preferences for vehicleId={}", request.vehicleId());
        }

        var saved = preferencesRepository.save(preference);
        log.info("Saved preferences: id={}, vehicleId={}", saved.getId(), saved.getVehicleId());

        return saved;
    }

    /**
     * Updates specific preference fields without replacing the entire map.
     */
    @Transactional
    public VehicleCarePreference mergePreferences(
            @NonNull UUID vehicleId,
            @NonNull Map<String, Object> partialPreferences,
            UUID updatedByUserId) {

        log.info("Merging preferences for vehicleId={}, keys={}", vehicleId, partialPreferences.keySet());

        var existing = getPreferences(vehicleId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No preferences found for vehicle: " + vehicleId));

        // Merge new values into existing preferences
        var currentPreferences = existing.getPreferences();
        currentPreferences.putAll(partialPreferences);

        existing.setPreferences(currentPreferences);
        if (updatedByUserId != null) {
            existing.setUpdatedByUserId(updatedByUserId);
        }

        var saved = preferencesRepository.save(existing);
        log.info("Merged preferences: id={}, vehicleId={}", saved.getId(), saved.getVehicleId());

        return saved;
    }

    /**
     * Deletes preferences for a vehicle.
     */
    @Transactional
    public void deletePreferences(@NonNull UUID vehicleId) {
        log.info("Deleting preferences for vehicleId={}", vehicleId);

        preferencesRepository.findByVehicleId(vehicleId)
                .ifPresent(preference -> {
                    preferencesRepository.delete(preference);
                    log.info("Deleted preferences: id={}", preference.getId());
                });
    }

    /**
     * Request record for upserting preferences.
     */
    public record UpsertPreferencesRequest(
            @NonNull UUID vehicleId,
            @NonNull Map<String, Object> preferences,
            String serviceNotes,
            UUID createdByUserId,
            UUID updatedByUserId) {
    }
}
