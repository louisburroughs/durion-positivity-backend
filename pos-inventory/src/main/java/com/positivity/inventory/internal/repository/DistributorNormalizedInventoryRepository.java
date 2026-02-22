package com.positivity.inventory.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.inventory.internal.entity.DistributorNormalizedInventory;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for normalized distributor inventory records.
 *
 * Issue: CAP-170 (#47)
 */
public interface DistributorNormalizedInventoryRepository extends JpaRepository<DistributorNormalizedInventory, UUID> {

    Optional<DistributorNormalizedInventory> findByDistributorIdAndDistributorSku(String distributorId,
            String distributorSku);
}
