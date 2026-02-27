package com.positivity.vehicle.internal.service;

import com.positivity.shared.dto.CreateVehicleRequest;
import com.positivity.shared.dto.UpdateVehicleRequest;
import com.positivity.shared.dto.VehicleResponse;
import com.positivity.vehicle.internal.entity.VehicleRecord;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;
import com.positivity.vehicle.internal.util.VinUtils;
import com.positivity.vehicle.service.VehicleService;

import jakarta.persistence.EntityNotFoundException;
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
public class VehicleServiceImpl implements VehicleService {

    private final VehicleRecordRepository vehicleRepository;

    /**
     * Creates a new vehicle with VIN validation and normalization.
     */
    @Override
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
    @Override
    @Transactional(readOnly = true)
    public Optional<VehicleResponse> getVehicle(@NonNull UUID vehicleId) {
        return vehicleRepository.findByVehicleId(vehicleId)
                .map(this::mapToResponse);
    }

    /**
     * Gets a vehicle by VIN (normalized lookup).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<VehicleResponse> getVehicleByVin(@NonNull String vin) {
        String vinNormalized = VinUtils.normalize(vin);
        return vehicleRepository.findByVinNormalized(vinNormalized)
                .map(this::mapToResponse);
    }

    /**
     * Updates a vehicle.
     */
    @Override
    @Transactional
    public VehicleResponse updateVehicle(@NonNull UUID vehicleId, @NonNull UpdateVehicleRequest request) {
        log.info("Updating vehicle {}", vehicleId);

        VehicleRecord vehicle = vehicleRepository.findByVehicleId(vehicleId)
                .orElseThrow(() -> new EntityNotFoundException("Vehicle not found: " + vehicleId));

        // Patch semantics: only update fields that are explicitly provided.
        if (request.getDescription() != null) {
            vehicle.setDescription(request.getDescription());
        }
        if (request.getUnitNumber() != null) {
            vehicle.setUnitNumber(request.getUnitNumber());
        }
        if (request.getLicensePlate() != null) {
            vehicle.setLicensePlate(request.getLicensePlate());
        }
        if (request.getLicensePlateJurisdiction() != null) {
            vehicle.setLicensePlateJurisdiction(request.getLicensePlateJurisdiction());
        }
        if (request.getYear() != null) {
            vehicle.setYear(request.getYear());
        }
        if (request.getMake() != null) {
            vehicle.setMake(request.getMake());
        }
        if (request.getModel() != null) {
            vehicle.setModel(request.getModel());
        }
        if (request.getTrim() != null) {
            vehicle.setTrim(request.getTrim());
        }

        VehicleRecord saved = vehicleRepository.save(vehicle);
        log.info("Updated vehicle {}", vehicleId);

        return mapToResponse(saved);
    }

    /**
     * Deletes (deactivates) a vehicle.
     */
    @Override
    @Transactional
    public void deleteVehicle(@NonNull UUID vehicleId) {
        log.info("Deactivating vehicle {}", vehicleId);

        VehicleRecord vehicle = vehicleRepository.findByVehicleId(vehicleId)
                .orElseThrow(() -> new EntityNotFoundException("Vehicle not found: " + vehicleId));

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
