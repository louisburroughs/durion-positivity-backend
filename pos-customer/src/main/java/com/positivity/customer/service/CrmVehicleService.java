package com.positivity.customer.service;

import com.positivity.customer.internal.client.VehicleInventoryClient;
import com.positivity.customer.internal.dto.CreateVehicleForPartyRequest;
import com.positivity.customer.internal.dto.VehicleTransferRequest;
import com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO;
import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.vehicle.internal.dto.CreateVehicleRequest;
import com.positivity.vehicle.internal.dto.VehicleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing customer-vehicle associations.
 * Handles CRUD operations for vehicles and their relationships to customers
 * (parties).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrmVehicleService {

    private final VehicleInventoryClient vehicleInventoryClient;
    private final PersonPartyRepository personPartyRepository;
    private final CommercialPartyRepository commercialPartyRepository;

    /**
     * Creates a new vehicle and associates it with a customer.
     */
    @Transactional
    public VehicleResponse createVehicle(@NonNull String customerId, @NonNull CreateVehicleForPartyRequest request) {
        log.debug("Creating vehicle for customer: {}", customerId);

        UUID customerUuid = parseCustomerId(customerId);
        AbstractParty party = findPartyOrThrow(customerUuid);

        // Map to vehicle inventory request
        CreateVehicleRequest vehicleRequest = CreateVehicleRequest.builder()
                .accountId(party.getPartyId())
                .vin(request.getVinNumber())
                .unitNumber(request.getUnitNumber() != null ? request.getUnitNumber() : "")
                .description(request.getDescription() != null ? request.getDescription() : "")
                .licensePlate(request.getLicensePlate())
                .licensePlateJurisdiction(request.getLicensePlateRegion())
                .build();

        VehicleResponse response = vehicleInventoryClient.createVehicle(vehicleRequest);

        // Associate VIN with party
        if (response != null && response.getVin() != null) {
            party.getVehicleVins().add(response.getVin());
            saveParty(party);
            log.info("Associated vehicle VIN {} with customer {}", response.getVin(), customerId);
        }

        return response;
    }

    /**
     * Retrieves a vehicle for a specific customer.
     */
    @Transactional(readOnly = true)
    public Optional<VehicleResponse> getVehicleForCustomer(@NonNull String customerId, @NonNull String vehicleId) {
        log.debug("Fetching vehicle {} for customer {}", vehicleId, customerId);

        UUID customerUuid = parseCustomerId(customerId);
        UUID vehicleUuid = parseVehicleId(vehicleId);

        // Verify the customer exists
        AbstractParty party = findPartyOrThrow(customerUuid);

        // Fetch the vehicle
        Optional<VehicleResponse> vehicleOpt = vehicleInventoryClient.getVehicle(vehicleUuid);

        if (vehicleOpt.isPresent()) {
            VehicleResponse vehicle = vehicleOpt.get();
            // Verify the vehicle belongs to this customer
            if (!party.getVehicleVins().contains(vehicle.getVin())) {
                log.warn("Vehicle {} does not belong to customer {}", vehicleId, customerId);
                return Optional.empty();
            }
        }

        return vehicleOpt;
    }

    /**
     * Updates a vehicle for a customer.
     */
    @Transactional
    public VehicleResponse updateVehicle(@NonNull String customerId, @NonNull CreateVehicleForPartyRequest request,
            @NonNull String vehicleId) {
        log.debug("Updating vehicle {} for customer {}", vehicleId, customerId);

        UUID customerUuid = parseCustomerId(customerId);
        UUID vehicleUuid = parseVehicleId(vehicleId);
        AbstractParty party = findPartyOrThrow(customerUuid);

        // Fetch existing vehicle to verify ownership
        VehicleResponse existing = vehicleInventoryClient.getVehicle(vehicleUuid)
                .orElseThrow(() -> new IllegalArgumentException("Vehicle not found: " + vehicleId));

        if (!party.getVehicleVins().contains(existing.getVin())) {
            throw new IllegalArgumentException(
                    "Vehicle " + vehicleId + " does not belong to customer " + customerId);
        }

        // Map to vehicle inventory request
        CreateVehicleRequest vehicleRequest = CreateVehicleRequest.builder()
                .accountId(party.getPartyId())
                .vin(request.getVinNumber() != null ? request.getVinNumber() : existing.getVin())
                .unitNumber(request.getUnitNumber() != null ? request.getUnitNumber() : existing.getUnitNumber())
                .description(request.getDescription() != null ? request.getDescription() : existing.getDescription())
                .licensePlate(
                        request.getLicensePlate() != null ? request.getLicensePlate() : existing.getLicensePlate())
                .licensePlateJurisdiction(request.getLicensePlateRegion() != null ? request.getLicensePlateRegion()
                        : existing.getLicensePlateJurisdiction())
                .build();

        return vehicleInventoryClient.updateVehicle(vehicleUuid, vehicleRequest);
    }

    /**
     * Deletes (deactivates) a vehicle for a customer.
     */
    @Transactional
    public void deleteVehicle(@NonNull String customerId, @NonNull String vehicleId) {
        log.debug("Deleting vehicle {} for customer {}", vehicleId, customerId);

        UUID customerUuid = parseCustomerId(customerId);
        UUID vehicleUuid = parseVehicleId(vehicleId);
        AbstractParty party = findPartyOrThrow(customerUuid);

        // Fetch vehicle to verify ownership
        VehicleResponse vehicle = vehicleInventoryClient.getVehicle(vehicleUuid)
                .orElseThrow(() -> new IllegalArgumentException("Vehicle not found: " + vehicleId));

        if (!party.getVehicleVins().contains(vehicle.getVin())) {
            throw new IllegalArgumentException(
                    "Vehicle " + vehicleId + " does not belong to customer " + customerId);
        }

        // Delete from vehicle inventory
        vehicleInventoryClient.deleteVehicle(vehicleUuid);

        // Remove VIN association from party
        party.getVehicleVins().remove(vehicle.getVin());
        saveParty(party);

        log.info("Deleted vehicle {} for customer {}", vehicleId, customerId);
    }

    /**
     * Transfers a vehicle from one customer to another.
     */
    @Transactional
    public VehicleResponse transferVehicle(@NonNull String sourceCustomerId, @NonNull String vehicleId,
            @NonNull VehicleTransferRequest request) {
        log.debug("Transferring vehicle {} from {} to {}", vehicleId, sourceCustomerId, request.getTargetCustomerId());

        UUID sourceUuid = parseCustomerId(sourceCustomerId);
        UUID targetUuid = parseCustomerId(request.getTargetCustomerId());
        UUID vehicleUuid = parseVehicleId(vehicleId);

        AbstractParty sourceParty = findPartyOrThrow(sourceUuid);
        AbstractParty targetParty = findPartyOrThrow(targetUuid);

        // Fetch vehicle to verify ownership
        VehicleResponse vehicle = vehicleInventoryClient.getVehicle(vehicleUuid)
                .orElseThrow(() -> new IllegalArgumentException("Vehicle not found: " + vehicleId));

        if (!sourceParty.getVehicleVins().contains(vehicle.getVin())) {
            throw new IllegalArgumentException(
                    "Vehicle " + vehicleId + " does not belong to source customer " + sourceCustomerId);
        }

        // Remove from source, add to target
        sourceParty.getVehicleVins().remove(vehicle.getVin());
        targetParty.getVehicleVins().add(vehicle.getVin());

        saveParty(sourceParty);
        saveParty(targetParty);

        // Update vehicle's accountId in vehicle inventory
        CreateVehicleRequest updateRequest = CreateVehicleRequest.builder()
                .accountId(targetParty.getPartyId())
                .vin(vehicle.getVin())
                .unitNumber(vehicle.getUnitNumber())
                .description(vehicle.getDescription())
                .licensePlate(vehicle.getLicensePlate())
                .licensePlateJurisdiction(vehicle.getLicensePlateJurisdiction())
                .year(vehicle.getYear())
                .make(vehicle.getMake())
                .model(vehicle.getModel())
                .trim(vehicle.getTrim())
                .build();

        VehicleResponse updated = vehicleInventoryClient.updateVehicle(vehicleUuid, updateRequest);

        log.info("Transferred vehicle {} from customer {} to customer {}", vehicleId, sourceCustomerId,
                request.getTargetCustomerId());

        return updated;
    }

    private UUID parseCustomerId(String customerId) {
        try {
            return UUID.fromString(customerId);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid customer ID format: {}", customerId);
            throw new IllegalArgumentException("Invalid customer ID format: " + customerId);
        }
    }

    private UUID parseVehicleId(String vehicleId) {
        try {
            return UUID.fromString(vehicleId);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid vehicle ID format: {}", vehicleId);
            throw new IllegalArgumentException("Invalid vehicle ID format: " + vehicleId);
        }
    }

    private AbstractParty findPartyOrThrow(UUID partyId) {
        return personPartyRepository.findById(partyId)
                .map(p -> (AbstractParty) p)
                .orElseGet(() -> commercialPartyRepository.findById(partyId)
                        .map(p -> (AbstractParty) p)
                        .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + partyId)));
    }

    private void saveParty(AbstractParty party) {
        if (party instanceof com.positivity.customer.internal.entity.PersonParty) {
            personPartyRepository.save((com.positivity.customer.internal.entity.PersonParty) party);
        } else if (party instanceof com.positivity.customer.internal.entity.CommercialParty) {
            commercialPartyRepository.save((com.positivity.customer.internal.entity.CommercialParty) party);
        }
    }

    @Transactional(readOnly = true)
    public CommercialParty findPartyByVehicleId(@NonNull String vehicleId) {
        log.debug("Finding party for vehicle: {}", vehicleId);
        UUID vehicleUuid = parseVehicleId(vehicleId);
        VehicleResponse vehicleData = vehicleInventoryClient.getVehicle(vehicleUuid).orElse(null);
        
        if (vehicleData == null || vehicleData.getVin() == null) {
            log.debug("Vehicle not found: {}", vehicleId);
            return null;
        }

        return findPartyByVin(vehicleData.getVin());
    }

    @Transactional(readOnly = true)
    public com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO.VehicleSummary fetchVehicleSummaryByVin(String vinCode) {
        try {
            VehicleResponse vehicleData = vehicleInventoryClient.getVehicleByVin(vinCode).orElse(null);
            
            if (vehicleData == null) {
                log.debug("Vehicle lookup by VIN returned null: {}", vinCode);
                return null;
            }
            
            com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO.VehicleSummary summary = 
                new com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO.VehicleSummary();
            summary.setVehicleId(vehicleData.getVehicleId().toString());
            summary.setVin(vehicleData.getVin());
            summary.setLicensePlate(vehicleData.getLicensePlate());
            summary.setMake(vehicleData.getMake());
            summary.setModel(vehicleData.getModel());
            summary.setYear(vehicleData.getYear());
            return summary;
        } catch (Exception ex) {
            log.warn("Vehicle fetch failed for VIN {}: {}", vinCode, ex.getMessage());
            return null;
        }
    }

    private CommercialParty findPartyByVin(String vinCode) {
        if (vinCode == null || vinCode.isBlank()) {
            return null;
        }
        
        // Search through commercial parties
        List<com.positivity.customer.internal.entity.CommercialParty> allParties = commercialPartyRepository.findAll();
        
        for (com.positivity.customer.internal.entity.CommercialParty candidate : allParties) {
            if (candidate.getVehicleVins().contains(vinCode)) {
                log.debug("Party located for VIN {}: {}", vinCode, candidate.getPartyId());
                return candidate;
            }
        }
        
        log.debug("No party found owning VIN: {}", vinCode);
        return null;
    }

    @Transactional(readOnly = true)
    public CrmSnapshotDTO buildSnapshotForVehicleOwner(@NonNull String vehicleId) {
        log.debug("Building snapshot via vehicle: {}", vehicleId);
        CommercialParty owner = findPartyByVehicleId(vehicleId);
        
        if (owner == null) {
            log.warn("No owner found for vehicle: {}", vehicleId);
            return null;
        }

        log.debug("Found owner {} for vehicle {}", owner.getPartyId(), vehicleId);
        
        // Delegate to party service but need to pass party ID
        return buildSnapshotForPartyEntity(owner);
    }

    private CrmSnapshotDTO buildSnapshotForPartyEntity(CommercialParty party) {
        com.positivity.customer.internal.dto.snapshot.SnapshotMetadata meta = 
            new com.positivity.customer.internal.dto.snapshot.SnapshotMetadata(
                java.util.UUID.randomUUID(),
                java.time.Instant.now(),
                "1.0.0"
            );

        com.positivity.customer.internal.dto.snapshot.AccountSummary acct = 
            new com.positivity.customer.internal.dto.snapshot.AccountSummary(
                party.getPartyId().toString(),
                party.getPartyNumber() != null ? party.getPartyNumber() : "N/A",
                party.getDisplayName() != null ? party.getDisplayName() : party.getLegalName(),
                party.getPartyType().name()
            );

        java.util.List<com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO.VehicleSummary> vehicles = 
            party.getVehicleVins().stream()
                .map(this::fetchVehicleSummaryByVin)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());

        CrmSnapshotDTO result = new CrmSnapshotDTO();
        result.setSnapshotMetadata(meta);
        result.setAccount(acct);
        result.setContacts(java.util.Collections.emptyList());
        result.setVehicles(vehicles);
        result.setPreferences(null);
        
        return result;
    }
}
