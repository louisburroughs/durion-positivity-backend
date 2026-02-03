package com.positivity.vehicle.service;

import com.positivity.vehicle.internal.entity.VehiclePartyAssociation;
import com.positivity.vehicle.internal.entity.VehiclePartyAssociation.AssociationType;
import com.positivity.vehicle.internal.repository.VehiclePartyAssociationRepository;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing vehicle-party associations (CAP:091 Story #104).
 * Handles temporal associations with implicit owner reassignment logic.
 * PUBLIC API for association management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleAssociationService {

    private final VehiclePartyAssociationRepository associationRepository;
    private final VehicleRecordRepository vehicleRepository;

    /**
     * Creates a new association. For OWNER type, automatically ends any existing
     * owner association.
     */
    @Transactional
    public VehiclePartyAssociation createAssociation(@NonNull CreateAssociationRequest request) {
        log.info("Creating association: vehicleId={}, partyId={}, type={}",
                request.vehicleId(), request.partyId(), request.associationType());

        // Validate vehicle exists
        if (!vehicleRepository.existsById(request.vehicleId())) {
            throw new EntityNotFoundException("Vehicle not found: " + request.vehicleId());
        }

        // For OWNER, end existing owner association atomically
        if (request.associationType() == AssociationType.OWNER) {
            endActiveAssociationOfType(request.vehicleId(), AssociationType.OWNER);
        }

        var association = VehiclePartyAssociation.builder()
                .vehicleId(request.vehicleId())
                .partyId(request.partyId())
                .associationType(request.associationType())
                .effectiveStartDate(request.effectiveStartDate() != null ? request.effectiveStartDate() : Instant.now())
                .build();

        var saved = associationRepository.save(association);
        log.info("Created association: id={}, vehicleId={}, type={}",
                saved.getAssociationId(), saved.getVehicleId(), saved.getAssociationType());

        return saved;
    }

    /**
     * Gets all currently active associations for a vehicle.
     */
    @Transactional(readOnly = true)
    public List<VehiclePartyAssociation> getActiveAssociations(@NonNull UUID vehicleId) {
        log.debug("Fetching active associations for vehicleId={}", vehicleId);
        return associationRepository.findAllActiveAssociations(vehicleId);
    }

    /**
     * Gets the current active association of a specific type.
     */
    @Transactional(readOnly = true)
    public Optional<VehiclePartyAssociation> getActiveAssociationByType(
            @NonNull UUID vehicleId,
            @NonNull AssociationType type) {
        log.debug("Fetching active {} association for vehicleId={}", type, vehicleId);
        return associationRepository.findActiveAssociation(vehicleId, type);
    }

    /**
     * Gets historical associations of a specific type as of a specific point in
     * time.
     */
    @Transactional(readOnly = true)
    public List<VehiclePartyAssociation> getAssociationsAsOf(
            @NonNull UUID vehicleId,
            @NonNull AssociationType type,
            @NonNull Instant asOfDate) {
        log.debug("Fetching {} associations for vehicleId={} as of {}", type, vehicleId, asOfDate);
        return associationRepository.findAssociationsAsOf(vehicleId, type, asOfDate);
    }

    /**
     * Reassigns vehicle owner by ending current owner association and creating new
     * one.
     */
    @Transactional
    public VehiclePartyAssociation reassignOwner(
            @NonNull UUID vehicleId,
            @NonNull UUID newOwnerId,
            Instant effectiveDate) {
        log.info("Reassigning owner for vehicleId={} to partyId={}", vehicleId, newOwnerId);

        var request = new CreateAssociationRequest(
                vehicleId,
                newOwnerId,
                AssociationType.OWNER,
                effectiveDate != null ? effectiveDate : Instant.now());

        return createAssociation(request);
    }

    /**
     * Ends an active association by setting its effectiveEndDate.
     */
    @Transactional
    public void endAssociation(@NonNull UUID associationId, Instant endDate) {
        log.info("Ending association: id={}, endDate={}", associationId, endDate);

        var association = associationRepository.findById(associationId)
                .orElseThrow(() -> new EntityNotFoundException("Association not found: " + associationId));

        if (association.getEffectiveEndDate() != null) {
            log.warn("Association already ended: id={}", associationId);
            return;
        }

        association.setEffectiveEndDate(endDate != null ? endDate : Instant.now());
        associationRepository.save(association);

        log.info("Ended association: id={}, vehicleId={}, type={}",
                association.getAssociationId(), association.getVehicleId(), association.getAssociationType());
    }

    /**
     * Internal: Ends the active association of a specific type for a vehicle.
     */
    private void endActiveAssociationOfType(UUID vehicleId, AssociationType type) {
        associationRepository.findActiveAssociation(vehicleId, type)
                .ifPresent(existing -> {
                    log.info("Ending existing {} association for vehicleId={}", type, vehicleId);
                    existing.setEffectiveEndDate(Instant.now());
                    associationRepository.save(existing);
                });
    }

    /**
     * Request record for creating associations.
     */
    public record CreateAssociationRequest(
            @NonNull UUID vehicleId,
            @NonNull UUID partyId,
            @NonNull AssociationType associationType,
            Instant effectiveStartDate) {
    }
}
