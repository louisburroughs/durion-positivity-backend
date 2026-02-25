package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReplenishmentPolicyRepository extends JpaRepository<ReplenishmentPolicy, UUID> {

    Optional<ReplenishmentPolicy> findByItemSKUAndLocationId(String itemSKU, UUID locationId);

    List<ReplenishmentPolicy> findByLocationId(UUID locationId);
}
