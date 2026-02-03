package com.positivity.vehicle.service;

import com.positivity.vehicle.internal.dto.CreateVehicleRequest;
import com.positivity.vehicle.internal.dto.VehicleResponse;
import com.positivity.vehicle.internal.entity.VehicleRecord;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;
import com.positivity.vehicle.internal.util.VinUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for vehicle CRUD operations - CAP:091 Story #105.
 * PUBLIC API for vehicle management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRecordRepository vehicleRepository;

    /**
     * Creates a new vehicle with VIN validation and normalization.
     */
    @Transactional
    public VehicleResponse createVehicle(@NonNull CreateVehicleRequest request) {
        log.info("Creating vehicle for account {} with VIN {}", request.getAccountId(), request.getVin());

        // Validate and normalize VIN
        String vinNormalized = VinUtils.validateAndNormalize(request.getVin());

        // Check global uniqueness
        if (vehicleRepository.existsByVinNormalizedAndIsActiveTrue(vinNormalized)) {
            throw new IllegalArgumentException(
                    "Vehicle with VIN " + request.getVin() + " already exists. " +
                            "VINs must be globally unique across all active vehicles.");
        }

        // Create entity
        VehicleRecord vehicle = VehicleRecord.builder()
                .accountId(request.getAccountId())
                .vin(request.getVin())
                .vinNormalized(vinNormalized)
                .unitNumber(request.getUnitNumber())
                .description(request.getDescription())
                .licensePlate(request.getLicensePlate())
                .licensePlateJurisdiction(request.getLicensePlateJurisdiction())
                .year(request.getYear())
                .make(request.getMake())
                .model(request.getModel())
                .trim(request.getTrim())
                .isActive(true)
                .build();

        VehicleRecord saved = vehicleRepository.save(vehicle);
        log.info("Created vehicle with ID {} for account {}", saved.getVehicleId(), saved.getAccountId());

        return mapToResponse(saved);
    }

    /**
     * Gets a vehicle by ID.
     */
    @Transactional(readOnly = true)
    public Optional<VehicleResponse> getVehicle(@NonNull UUID vehicleId) {
        return vehicleRepository.findByVehicleId(vehicleId)
                .map(this::mapToResponse);
    }

    /**
     * Gets a vehicle by VIN (normalized lookup).
     */
    @Transactional(readOnly = true)
    public Optional<VehicleResponse> getVehicleByVin(@NonNull String vin) {
        String vinNormalized = VinUtils.normalize(vin);
        return vehicleRepository.findByVinNormalized(vinNormalized)
                .map(this::mapToResponse);
    }

    /**
     * Updates a vehicle.
     */
    @Transactional
    public VehicleResponse updateVehicle(@NonNull UUID vehicleId, @NonNull CreateVehicleRequest request) {
        log.info("Updating vehicle {}", vehicleId);

        VehicleRecord vehicle = vehicleRepository.findByVehicleId(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("Vehicle not found: " + vehicleId));

        // Update fields
        vehicle.setDescription(request.getDescription());
        vehicle.setUnitNumber(request.getUnitNumber());
        vehicle.setLicensePlate(request.getLicensePlate());
        vehicle.setLicensePlateJurisdiction(request.getLicensePlateJurisdiction());
        vehicle.setYear(request.getYear());
        vehicle.setMake(request.getMake());
        vehicle.setModel(request.getModel());
        vehicle.setTrim(request.getTrim());

        VehicleRecord saved = vehicleRepository.save(vehicle);
        log.info("Updated vehicle {}", vehicleId);

        return mapToResponse(saved);
    }

    /**
     * Deletes (deactivates) a vehicle.
     */
    @Transactional
    public void deleteVehicle(@NonNull UUID vehicleId) {
        log.info("Deactivating vehicle {}", vehicleId);

        VehicleRecord vehicle = vehicleRepository.findByVehicleId(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("Vehicle not found: " + vehicleId));

        vehicle.setIsActive(false);
        vehicleRepository.save(vehicle);

        log.info("Deactivated vehicle {}", vehicleId);
    }

    private VehicleResponse mapToResponse(VehicleRecord vehicle) {
        return VehicleResponse.builder()
                .vehicleId(vehicle.getVehicleId())
                .accountId(vehicle.getAccountId())
                .vin(vehicle.getVin())
                .vinNormalized(vehicle.getVinNormalized())
                .unitNumber(vehicle.getUnitNumber())
                .description(vehicle.getDescription())
                .licensePlate(vehicle.getLicensePlate())
                .licensePlateJurisdiction(vehicle.getLicensePlateJurisdiction())
                .year(vehicle.getYear())
                .make(vehicle.getMake())
                .model(vehicle.getModel())
                .trim(vehicle.getTrim())
                .isActive(vehicle.getIsActive())
                .createdAt(vehicle.getCreatedAt())
                .updatedAt(vehicle.getUpdatedAt())
                .version(vehicle.getVersion())
                .build();
    }
}
