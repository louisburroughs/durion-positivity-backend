package com.positivity.vehicle.internal.service;

import com.positivity.vehicle.internal.dao.VehicleDao;
import com.positivity.vehicle.internal.dto.VehicleLegacyMapper;
import com.positivity.vehicle.internal.dto.VehicleLegacyRequest;
import com.positivity.vehicle.internal.dto.VehicleLegacyResponse;
import com.positivity.vehicle.service.VehicleLegacyService;
import java.time.Year;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Service implementation for legacy vehicle CRUD operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleLegacyServiceImpl implements VehicleLegacyService {

    private static final int MIN_MODEL_YEAR = 1886;
    private static final int FUTURE_YEAR_BUFFER = 1;
    private static final Set<String> ALLOWED_VEHICLE_TYPES =
            Set.of("CAR", "VAN", "COMMERCIAL_TRUCK", "PASSENGER_TRUCK", "TRUCK");

    private final VehicleDao vehicleDao;

    @Override
    @NonNull
    public VehicleLegacyResponse createVehicle(@NonNull VehicleLegacyRequest vehicle) {
        validateVehicleRequest(vehicle, false);
        var entity = VehicleLegacyMapper.toEntity(vehicle);
        normalizeOptionalVin(vehicle.getVin()).ifPresent(entity::setVIN);

        var saved = vehicleDao.save(entity);
        log.info("Created legacy vehicle");
        return VehicleLegacyMapper.toResponse(saved);
    }

    @Override
    public Optional<VehicleLegacyResponse> getVehicle(@NonNull UUID id) {
        validateId(id);
        return vehicleDao.findById(id).map(VehicleLegacyMapper::toResponse);
    }

    @Override
    @NonNull
    public List<VehicleLegacyResponse> getAllVehicles() {
        return vehicleDao.findAll().stream()
                .map(VehicleLegacyMapper::toResponse)
                .toList();
    }

    @Override
    public Optional<VehicleLegacyResponse> updateVehicle(@NonNull UUID id, @NonNull VehicleLegacyRequest updated) {
        validateId(id);
        validateVehicleRequest(updated, false);

        var existing = vehicleDao.findById(id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        var vehicle = existing.get();
        VehicleLegacyMapper.applyCoreFields(vehicle, updated);
        var saved = vehicleDao.save(vehicle);
        log.info("Updated legacy vehicle with id {}", id);
        return Optional.of(VehicleLegacyMapper.toResponse(saved));
    }

    @Override
    public boolean deleteVehicle(@NonNull UUID id) {
        validateId(id);
        if (vehicleDao.findById(id).isEmpty()) {
            return false;
        }
        vehicleDao.deleteById(id);
        log.info("Deleted legacy vehicle with id {}", id);
        return true;
    }

    @Override
    @NonNull
    public VehicleLegacyResponse createVehicleByVin(@NonNull VehicleLegacyRequest vehicle) {
        validateVehicleRequest(vehicle, true);
        var entity = VehicleLegacyMapper.toEntity(vehicle);
        entity.setVIN(normalizeRequiredVin(vehicle.getVin(), "vin"));

        var saved = vehicleDao.save(entity);
        log.info("Created legacy vehicle with VIN {}", entity.getVIN());
        return VehicleLegacyMapper.toResponse(saved);
    }

    @Override
    public Optional<VehicleLegacyResponse> getVehicleByVin(@NonNull String vin) {
        var normalizedVin = normalizeRequiredVin(vin, "vin");
        return vehicleDao.findByVIN(normalizedVin).map(VehicleLegacyMapper::toResponse);
    }

    @Override
    public Optional<VehicleLegacyResponse> updateVehicleByVin(
            @NonNull String vin, @NonNull VehicleLegacyRequest updated) {
        var normalizedVin = normalizeRequiredVin(vin, "vin");
        validateVehicleRequest(updated, false);

        var existing = vehicleDao.findByVIN(normalizedVin);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        var vehicle = existing.get();
        VehicleLegacyMapper.applyCoreFields(vehicle, updated);
        var saved = vehicleDao.save(vehicle);
        log.info("Updated legacy vehicle with VIN {}", normalizedVin);
        return Optional.of(VehicleLegacyMapper.toResponse(saved));
    }

    @Override
    public boolean deleteVehicleByVin(@NonNull String vin) {
        var normalizedVin = normalizeRequiredVin(vin, "vin");
        if (vehicleDao.findByVIN(normalizedVin).isEmpty()) {
            return false;
        }
        vehicleDao.deleteByVIN(normalizedVin);
        log.info("Deleted legacy vehicle with VIN {}", normalizedVin);
        return true;
    }

    private void validateVehicleRequest(@NonNull VehicleLegacyRequest request, boolean vinRequired) {
        if (request == null) {
            throw new IllegalArgumentException("Vehicle request cannot be null");
        }

        validateRequiredText(request.getMake(), "make");
        validateRequiredText(request.getModel(), "model");
        validateYear(request.getYear());
        validateVehicleType(request.getVehicleType());

        if (vinRequired) {
            normalizeRequiredVin(request.getVin(), "vin");
            return;
        }

        normalizeOptionalVin(request.getVin());
    }

    private void validateRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private void validateVehicleType(String vehicleType) {
        if (vehicleType == null) {
            return;
        }

        var normalizedType = vehicleType.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_VEHICLE_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("Unsupported vehicleType: " + vehicleType);
        }
    }

    private void validateYear(Integer year) {
        if (year == null) {
            throw new IllegalArgumentException("year is required");
        }

        int maxYear = Year.now().getValue() + FUTURE_YEAR_BUFFER;
        if (year < MIN_MODEL_YEAR || year > maxYear) {
            throw new IllegalArgumentException("year must be between " + MIN_MODEL_YEAR + " and " + maxYear);
        }
    }

    private void validateId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
    }

    private Optional<String> normalizeOptionalVin(String vin) {
        if (vin == null) {
            return Optional.empty();
        }

        var normalized = vin.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("vin must not be blank");
        }
        return Optional.of(normalized);
    }

    private String normalizeRequiredVin(String vin, String fieldName) {
        if (vin == null || vin.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return vin.trim();
    }
}
