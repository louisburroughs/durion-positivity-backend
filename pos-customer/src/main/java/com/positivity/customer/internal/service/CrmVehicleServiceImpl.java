package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO;
import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.ExtVehicle;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.service.CrmVehicleService;
import com.positivity.shared.dto.VehicleResponse;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side of the customer-vehicle relationship (ADR-0044 §6, #843).
 *
 * <p>All vehicle facts are served from the local {@code ext_vehicle} replica fed by
 * {@code vehicle.events.v1}; the previous synchronous {@code VehicleInventoryClient} is retired.
 * Vehicle registry writes (create/update/transfer/deactivate) go straight to
 * pos-vehicle-inventory through the gateway; the vehicle-party association this module owns
 * (ADR-0012) follows via the event consumer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrmVehicleServiceImpl implements CrmVehicleService {
    private final Clock clock;

    private final ExtVehicleRepository extVehicleRepository;
    private final PersonPartyRepository personPartyRepository;
    private final CommercialPartyRepository commercialPartyRepository;

    /**
     * Retrieves a vehicle for a specific customer.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<VehicleResponse> getVehicleForCustomer(@NonNull UUID customerId, @NonNull UUID vehicleId) {
        log.debug("Fetching vehicle {} for customer {}", vehicleId, customerId);

        AbstractParty party = findPartyOrThrow(customerId);

        return extVehicleRepository
                .findById(vehicleId)
                .filter(vehicle -> {
                    if (party.getVehicleVins().contains(vehicle.getVin())) {
                        return true;
                    }
                    log.warn("Vehicle {} does not belong to customer {}", vehicleId, customerId);
                    return false;
                })
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public CommercialParty findPartyByVehicleId(@NonNull UUID vehicleId) {
        log.debug("Finding party for vehicle: {}", vehicleId);
        return extVehicleRepository
                .findById(vehicleId)
                .map(vehicle -> findPartyByVin(vehicle.getVin()))
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public CrmSnapshotDTO.VehicleSummary fetchVehicleSummaryByVin(String vinCode) {
        return findReplicaByVin(vinCode).map(this::toSummary).orElse(null);
    }

    private CommercialParty findPartyByVin(String vinCode) {
        if (vinCode == null || vinCode.isBlank()) {
            return null;
        }
        List<CommercialParty> holders = commercialPartyRepository.findByVehicleVin(vinCode);
        if (holders.isEmpty()) {
            log.debug("No party found owning VIN: {}", vinCode);
            return null;
        }
        return holders.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public CrmSnapshotDTO buildSnapshotForVehicleOwner(@NonNull UUID vehicleId) {
        log.debug("Building snapshot via vehicle: {}", vehicleId);
        CommercialParty owner = findPartyByVehicleId(vehicleId);

        if (owner == null) {
            log.warn("No owner found for vehicle: {}", vehicleId);
            return null;
        }

        log.debug("Found owner {} for vehicle {}", owner.getPartyId(), vehicleId);

        // Delegate to shared snapshot builder
        return buildSnapshotForOwnerParty(owner);
    }

    @Override
    public CrmSnapshotDTO buildSnapshotForOwnerParty(CommercialParty party) {
        com.positivity.customer.internal.dto.snapshot.SnapshotMetadata meta =
                new com.positivity.customer.internal.dto.snapshot.SnapshotMetadata(
                        UUIDv7Generator.generate(), Instant.now(clock), "1.0.0");

        com.positivity.customer.internal.dto.snapshot.AccountSummary acct =
                new com.positivity.customer.internal.dto.snapshot.AccountSummary(
                        party.getPartyId().toString(),
                        party.getCustomerNumber() != null ? party.getCustomerNumber() : "N/A",
                        party.getDisplayName() != null ? party.getDisplayName() : party.getLegalName(),
                        party.getPartyType().name());

        List<CrmSnapshotDTO.VehicleSummary> vehicles = collectVehiclesForParty(party);

        CrmSnapshotDTO result = new CrmSnapshotDTO();
        result.setSnapshotMetadata(meta);
        result.setAccount(acct);
        result.setContacts(java.util.Collections.emptyList());
        result.setVehicles(vehicles);
        result.setPreferences(null);

        return result;
    }

    @Override
    public List<CrmSnapshotDTO.VehicleSummary> collectVehiclesForParty(CommercialParty party) {
        return collectVehicleSummaries(party);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<List<CrmSnapshotDTO.VehicleSummary>> findVehiclesForCustomer(@NonNull UUID customerId) {
        log.debug("Listing vehicles for customer: {}", customerId);
        AbstractParty party = personPartyRepository
                .findById(customerId)
                .map(p -> (AbstractParty) p)
                .or(() -> commercialPartyRepository.findById(customerId).map(p -> (AbstractParty) p))
                .orElse(null);
        if (party == null) {
            return Optional.empty();
        }
        return Optional.of(collectVehicleSummaries(party));
    }

    /**
     * Resolves the VIN set of any party to vehicle summaries from the {@code ext_vehicle}
     * replica, dropping VINs the replica cannot resolve (e.g. events not yet consumed).
     */
    private List<CrmSnapshotDTO.VehicleSummary> collectVehicleSummaries(AbstractParty party) {
        return party.getVehicleVins().stream()
                .map(vin -> findReplicaByVin(vin).map(this::toSummary).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private Optional<ExtVehicle> findReplicaByVin(String vin) {
        if (vin == null || vin.isBlank()) {
            return Optional.empty();
        }
        // Mirrors the owner's VIN normalization (VinUtils): uppercase without surrounding
        // whitespace, matching ext_vehicle.vin_normalized.
        return extVehicleRepository.findByVinNormalizedAndActiveTrue(vin.trim().toUpperCase(Locale.ROOT));
    }

    private CrmSnapshotDTO.VehicleSummary toSummary(ExtVehicle vehicle) {
        CrmSnapshotDTO.VehicleSummary summary = new CrmSnapshotDTO.VehicleSummary();
        summary.setVehicleId(vehicle.getVehicleId().toString());
        summary.setVin(vehicle.getVin());
        summary.setLicensePlate(vehicle.getLicensePlate());
        summary.setMake(vehicle.getMake());
        summary.setModel(vehicle.getModel());
        summary.setYear(vehicle.getYear());
        return summary;
    }

    private VehicleResponse mapToResponse(ExtVehicle vehicle) {
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
                .isActive(vehicle.isActive())
                .createdAt(vehicle.getVehicleCreatedAt())
                .updatedAt(vehicle.getVehicleUpdatedAt())
                .version(vehicle.getAggregateVersion())
                .build();
    }

    private AbstractParty findPartyOrThrow(UUID partyId) {
        return personPartyRepository
                .findById(partyId)
                .map(p -> (AbstractParty) p)
                .orElseGet(() -> commercialPartyRepository
                        .findById(partyId)
                        .map(p -> (AbstractParty) p)
                        .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + partyId)));
    }
}
