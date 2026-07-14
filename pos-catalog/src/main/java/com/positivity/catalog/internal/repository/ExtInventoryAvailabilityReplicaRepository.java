package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ExtInventoryAvailabilityReplica;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtInventoryAvailabilityReplicaRepository
        extends JpaRepository<ExtInventoryAvailabilityReplica, UUID> {

    Optional<ExtInventoryAvailabilityReplica> findByStockItemIdAndLocationId(String stockItemId, UUID locationId);
}
