package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplenishmentPolicyRepository extends JpaRepository<ReplenishmentPolicy, UUID> {

    Optional<ReplenishmentPolicy> findByItemSKUAndLocationId(String itemSKU, UUID locationId);

    List<ReplenishmentPolicy> findByLocationId(UUID locationId);

    // #1514 removed three methods that existed only to serve putaway:
    //   existsByItemSKU / existsByItemSKUAndLocationId  — the two gates that made putaway
    //     eligibility depend on a replenishment policy existing, so a brand-new SKU could never be
    //     put away anywhere;
    //   sumMaximumQuantityByLocationId  — the capacity fallback that treated the sum of a
    //     location's replenishment maximums as its bin capacity, so an undeclared bin computed
    //     max = 0 and hard-failed every putaway.
    // All three had no caller outside PutawayValidationServiceImpl and were removed with it. This
    // repository now serves only the replenishment scan engine, which is its documented job.

    /**
     * Every policy at a reachable site, or at a storage location (pick face) replicated under one
     * (ADR-0061 §3, #1872). Never called with an empty set — an empty reach is an empty page
     * decided before the query.
     */
    @Query("""
            SELECT p FROM ReplenishmentPolicy p
            WHERE p.locationId IN :locationIds
               OR p.locationId IN (SELECT s.storageLocationId FROM ExtStorageLocationReplica s
                                   WHERE s.siteId IN :locationIds)
            """)
    List<ReplenishmentPolicy> findWithinLocations(@Param("locationIds") Collection<UUID> locationIds);
}
