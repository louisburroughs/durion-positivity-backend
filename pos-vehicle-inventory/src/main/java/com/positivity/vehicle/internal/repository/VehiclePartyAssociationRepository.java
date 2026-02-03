package com.positivity.vehicle.internal.repository;

import com.positivity.vehicle.internal.entity.VehiclePartyAssociation;
import com.positivity.vehicle.internal.entity.VehiclePartyAssociation.AssociationType;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VehiclePartyAssociationRepository extends JpaRepository<VehiclePartyAssociation, UUID> {
    
    @Query("SELECT vpa FROM VehiclePartyAssociation vpa WHERE " +
           "vpa.vehicleId = :vehicleId AND " +
           "vpa.associationType = :type AND " +
           "vpa.effectiveEndDate IS NULL")
    Optional<VehiclePartyAssociation> findActiveAssociation(
            @Param("vehicleId") @NonNull UUID vehicleId,
            @Param("type") @NonNull AssociationType type);
    
    @Query("SELECT vpa FROM VehiclePartyAssociation vpa WHERE " +
           "vpa.vehicleId = :vehicleId AND " +
           "vpa.effectiveEndDate IS NULL")
    List<VehiclePartyAssociation> findAllActiveAssociations(@Param("vehicleId") @NonNull UUID vehicleId);
    
    @Query("SELECT vpa FROM VehiclePartyAssociation vpa WHERE " +
           "vpa.vehicleId = :vehicleId AND " +
           "vpa.associationType = :type AND " +
           "(vpa.effectiveEndDate IS NULL OR vpa.effectiveEndDate > :asOf)")
    List<VehiclePartyAssociation> findAssociationsAsOf(
            @Param("vehicleId") @NonNull UUID vehicleId,
            @Param("type") @NonNull AssociationType type,
            @Param("asOf") @NonNull Instant asOf);
}
