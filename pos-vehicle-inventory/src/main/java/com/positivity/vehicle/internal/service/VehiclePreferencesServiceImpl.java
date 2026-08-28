package com.positivity.vehicle.internal.service;

import com.positivity.domainevents.vehicle.VehicleCarePreferenceUpdatedV1;
import com.positivity.vehicle.internal.config.CarePreferenceEventPublisher;
import com.positivity.vehicle.internal.dto.UpsertPreferencesRequest;
import com.positivity.vehicle.internal.dto.VehicleCarePreferenceMapper;
import com.positivity.vehicle.internal.dto.VehicleCarePreferenceResponse;
import com.positivity.vehicle.internal.entity.VehicleCarePreference;
import com.positivity.vehicle.internal.repository.VehicleCarePreferenceRepository;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing vehicle care preferences (CAP:091 Story #102).
 * Handles flexible JSONB preference storage with validation.
 * PUBLIC API for preferences management.
 *
 * <p>The service interval is a structured column, not a blob key (#1175): a legacy
 * {@code serviceIntervalMonths} key arriving inside the preferences map is promoted into the
 * structured field so older clients keep working without the blob and column drifting apart.
 * Every mutation emits a {@code vehicle.care-preference.updated} fact for CRM consumers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehiclePreferencesServiceImpl implements VehiclePreferencesService {

    /** Legacy blob key promoted into the structured column by the V4 migration (#1175). */
    static final String LEGACY_INTERVAL_KEY = "serviceIntervalMonths";

    private static final int MIN_INTERVAL_MONTHS = VehicleCarePreferenceUpdatedV1.MIN_SERVICE_INTERVAL_MONTHS;
    private static final int MAX_INTERVAL_MONTHS = VehicleCarePreferenceUpdatedV1.MAX_SERVICE_INTERVAL_MONTHS;

    private final VehicleCarePreferenceRepository preferencesRepository;
    private final VehicleRecordRepository vehicleRepository;
    private final CarePreferenceEventPublisher carePreferenceEventPublisher;

    /**
     * Gets preferences for a vehicle. Returns empty Optional if not found.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<VehicleCarePreferenceResponse> getPreferences(@NonNull UUID vehicleId) {
        log.debug("Fetching preferences for vehicleId={}", vehicleId);
        return preferencesRepository.findByVehicle_VehicleId(vehicleId).map(VehicleCarePreferenceMapper::toResponse);
    }

    /**
     * Creates or updates preferences for a vehicle (upsert operation).
     */
    @Override
    @Transactional
    public VehicleCarePreferenceResponse upsertPreferences(@NonNull UpsertPreferencesRequest request) {
        log.info("Upserting preferences for vehicleId={}", request.getVehicleId());

        // Validate vehicle exists
        if (!vehicleRepository.existsById(request.getVehicleId())) {
            throw new EntityNotFoundException("Vehicle not found: " + request.getVehicleId());
        }

        // Validate preferences map is not null (can be empty)
        if (request.getPreferences() == null) {
            throw new IllegalArgumentException("Preferences map cannot be null (use empty map instead)");
        }

        Integer legacyInterval = extractLegacyInterval(request.getPreferences());
        // Boxed explicitly so the conditional stays Integer and a null legacyInterval survives.
        Integer serviceIntervalMonths = request.getServiceIntervalMonths() != null
                ? Integer.valueOf(validateInterval(request.getServiceIntervalMonths()))
                : legacyInterval;

        var existing = preferencesRepository.findByVehicle_VehicleId(request.getVehicleId());

        VehicleCarePreference preference;
        if (existing.isPresent()) {
            // Update existing; PUT is a full replace, so a null interval clears the override.
            preference = existing.get();
            preference.setPreferences(request.getPreferences());
            preference.setServiceIntervalMonths(serviceIntervalMonths);
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
                    .vehicle(vehicleRepository.getReferenceById(request.getVehicleId()))
                    .preferences(request.getPreferences())
                    .serviceIntervalMonths(serviceIntervalMonths)
                    .serviceNotes(request.getServiceNotes())
                    .createdByUserId(request.getCreatedByUserId())
                    .updatedByUserId(request.getUpdatedByUserId())
                    .build();
            log.info("Creating new preferences for vehicleId={}", request.getVehicleId());
        }

        var saved = preferencesRepository.save(preference);
        carePreferenceEventPublisher.publishCarePreferenceUpserted(saved);
        log.info("Saved preferences: id={}, vehicleId={}", saved.getId(), saved.getVehicleId());

        return VehicleCarePreferenceMapper.toResponse(saved);
    }

    /**
     * Updates specific preference fields without replacing the entire map.
     */
    @Override
    @Transactional
    public VehicleCarePreferenceResponse mergePreferences(
            @NonNull UUID vehicleId,
            @NonNull Map<String, Object> partialPreferences,
            Integer serviceIntervalMonths,
            UUID updatedByUserId) {

        log.info("Merging preferences for vehicleId={}, keys={}", vehicleId, partialPreferences.keySet());

        var existing = preferencesRepository
                .findByVehicle_VehicleId(vehicleId)
                .orElseThrow(() -> new EntityNotFoundException("No preferences found for vehicle: " + vehicleId));

        Integer legacyInterval = extractLegacyInterval(partialPreferences);
        Integer effectiveInterval = serviceIntervalMonths != null
                ? Integer.valueOf(validateInterval(serviceIntervalMonths))
                : legacyInterval;

        // Merge new values into existing preferences
        var currentPreferences = existing.getPreferences();
        currentPreferences.putAll(partialPreferences);
        // The legacy key never survives in the blob — the structured column is the single source.
        currentPreferences.remove(LEGACY_INTERVAL_KEY);

        existing.setPreferences(currentPreferences);
        if (effectiveInterval != null) {
            existing.setServiceIntervalMonths(effectiveInterval);
        }
        if (updatedByUserId != null) {
            existing.setUpdatedByUserId(updatedByUserId);
        }

        var saved = preferencesRepository.save(existing);
        carePreferenceEventPublisher.publishCarePreferenceUpserted(saved);
        log.info("Merged preferences: id={}, vehicleId={}", saved.getId(), saved.getVehicleId());

        return VehicleCarePreferenceMapper.toResponse(saved);
    }

    /**
     * Deletes preferences for a vehicle.
     */
    @Override
    @Transactional
    public void deletePreferences(@NonNull UUID vehicleId) {
        log.info("Deleting preferences for vehicleId={}", vehicleId);

        preferencesRepository.findByVehicle_VehicleId(vehicleId).ifPresent(preference -> {
            preferencesRepository.delete(preference);
            carePreferenceEventPublisher.publishCarePreferenceDeleted(vehicleId);
            log.info("Deleted preferences: id={}", preference.getId());
        });
    }

    /**
     * Removes the legacy {@code serviceIntervalMonths} key from the given map and returns its
     * validated value, or null when absent. Rejects non-numeric or out-of-range values so a bad
     * blob write cannot silently drop the interval (#1175).
     */
    private static @Nullable Integer extractLegacyInterval(Map<String, Object> preferences) {
        if (!preferences.containsKey(LEGACY_INTERVAL_KEY)) {
            return null;
        }
        Object raw = preferences.remove(LEGACY_INTERVAL_KEY);
        if (raw == null) {
            return null;
        }
        int value;
        if (raw instanceof Number number) {
            double asDouble = number.doubleValue();
            value = number.intValue();
            if (asDouble != value) {
                throw new IllegalArgumentException(LEGACY_INTERVAL_KEY + " must be a whole number but was: " + raw);
            }
        } else {
            try {
                value = Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(LEGACY_INTERVAL_KEY + " must be a whole number but was: " + raw);
            }
        }
        return validateInterval(value);
    }

    private static int validateInterval(int serviceIntervalMonths) {
        if (serviceIntervalMonths < MIN_INTERVAL_MONTHS || serviceIntervalMonths > MAX_INTERVAL_MONTHS) {
            throw new IllegalArgumentException("serviceIntervalMonths must be between " + MIN_INTERVAL_MONTHS + " and "
                    + MAX_INTERVAL_MONTHS + " but was: " + serviceIntervalMonths);
        }
        return serviceIntervalMonths;
    }
}
