package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
