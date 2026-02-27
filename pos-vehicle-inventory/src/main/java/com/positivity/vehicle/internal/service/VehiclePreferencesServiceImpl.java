package com.positivity.vehicle.internal.service;

import com.positivity.vehicle.internal.dto.UpsertPreferencesRequest;
import com.positivity.vehicle.internal.entity.VehicleCarePreference;
import com.positivity.vehicle.internal.repository.VehicleCarePreferenceRepository;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;
import com.positivity.vehicle.service.VehiclePreferencesService;

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
public class VehiclePreferencesServiceImpl implements VehiclePreferencesService {

    private final VehicleCarePreferenceRepository preferencesRepository;
    private final VehicleRecordRepository vehicleRepository;

    /**
     * Gets preferences for a vehicle. Returns empty Optional if not found.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<VehicleCarePreference> getPreferences(@NonNull UUID vehicleId) {
        log.debug("Fetching preferences for vehicleId={}", vehicleId);
        return preferencesRepository.findByVehicleId(vehicleId);
    }

    /**
     * Creates or updates preferences for a vehicle (upsert operation).
     */
    @Override
    @Transactional
    public VehicleCarePreference upsertPreferences(@NonNull UpsertPreferencesRequest request) {
        log.info("Upserting preferences for vehicleId={}", request.getVehicleId());

        // Validate vehicle exists
        if (!vehicleRepository.existsById(request.getVehicleId())) {
            throw new EntityNotFoundException("Vehicle not found: " + request.getVehicleId());
        }

        // Validate preferences map is not null (can be empty)
        if (request.getPreferences() == null) {
            throw new IllegalArgumentException("Preferences map cannot be null (use empty map instead)");
        }

        var existing = preferencesRepository.findByVehicleId(request.getVehicleId());

        VehicleCarePreference preference;
        if (existing.isPresent()) {
            // Update existing
            preference = existing.get();
            preference.setPreferences(request.getPreferences());
            if (request.getServiceNotes() != null) {
                preference.setServiceNotes(request.getServiceNotes());
            }
            if (request.getUpdatedByUserId() != null) {
                preference.setUpdatedByUserId(request.getUpdatedByUserId());
            }
            log.info("Updating existing preferences: id={}", preference.getId());
        } else {
            // Create new
            preference = VehicleCarePreference.builder()
                    .vehicleId(request.getVehicleId())
                    .preferences(request.getPreferences())
                    .serviceNotes(request.getServiceNotes())
                    .createdByUserId(request.getCreatedByUserId())
                    .updatedByUserId(request.getUpdatedByUserId())
                    .build();
            log.info("Creating new preferences for vehicleId={}", request.getVehicleId());
        }

        var saved = preferencesRepository.save(preference);
        log.info("Saved preferences: id={}, vehicleId={}", saved.getId(), saved.getVehicleId());

        return saved;
    }

    /**
     * Updates specific preference fields without replacing the entire map.
     */
    @Override
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
    @Override
    @Transactional
    public void deletePreferences(@NonNull UUID vehicleId) {
        log.info("Deleting preferences for vehicleId={}", vehicleId);

        preferencesRepository.findByVehicleId(vehicleId)
                .ifPresent(preference -> {
                    preferencesRepository.delete(preference);
                    log.info("Deleted preferences: id={}", preference.getId());
                });
    }
}
