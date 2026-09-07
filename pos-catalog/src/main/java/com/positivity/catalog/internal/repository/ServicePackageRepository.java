package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ServicePackageEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServicePackageRepository extends JpaRepository<ServicePackageEntity, UUID> {

    @NonNull
    Optional<ServicePackageEntity> findByPackageCode(@NonNull String packageCode);

    /**
     * Packages a location may sell: its own, plus every platform package. A location's own
     * packages come first so a shop's variant of a platform offering reads as the local one.
     *
     * <p>A null {@code locationId} matches only platform rows, which is the right answer for a
     * caller quoting as the platform.
     */
    @Query("""
            SELECT p FROM ServicePackageEntity p
            WHERE p.active = true
              AND (p.ownerLocationId IS NULL OR p.ownerLocationId = :locationId)
              AND (:fleetPartyId IS NULL OR p.fleetPartyId = :fleetPartyId)
              AND (:includeFleetPackages = true OR p.fleetPartyId IS NULL)
            ORDER BY p.ownerLocationId ASC NULLS LAST, p.packageCode ASC
            """)
    @NonNull
    List<ServicePackageEntity> findSellable(
            @Param("locationId") UUID locationId,
            @Param("fleetPartyId") UUID fleetPartyId,
            @Param("includeFleetPackages") boolean includeFleetPackages);

    @NonNull
    List<ServicePackageEntity> findByFleetPartyIdAndActiveTrueOrderByPackageCodeAsc(@NonNull UUID fleetPartyId);
}
