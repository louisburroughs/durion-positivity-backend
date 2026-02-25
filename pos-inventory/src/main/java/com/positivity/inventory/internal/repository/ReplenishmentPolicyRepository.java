package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReplenishmentPolicyRepository extends JpaRepository<ReplenishmentPolicy, UUID> {

    Optional<ReplenishmentPolicy> findByItemSKUAndLocationId(String itemSKU, String locationId);

    List<ReplenishmentPolicy> findByLocationId(String locationId);

    boolean existsByItemSKU(String itemSKU);

    boolean existsByItemSKUAndLocationId(String itemSKU, String locationId);

    @Query("""
            SELECT COALESCE(SUM(r.maximumQuantity), 0)
            FROM ReplenishmentPolicy r
            WHERE r.locationId = :locationId
            """)
    Integer sumMaximumQuantityByLocationId(@Param("locationId") String locationId);
}
