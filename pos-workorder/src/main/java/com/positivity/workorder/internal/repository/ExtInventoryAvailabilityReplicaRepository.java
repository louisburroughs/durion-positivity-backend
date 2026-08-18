package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtInventoryAvailabilityReplica;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Read side of the pos-inventory-owned availability replica (CAP #1315). */
@Repository
public interface ExtInventoryAvailabilityReplicaRepository
        extends JpaRepository<ExtInventoryAvailabilityReplica, UUID> {

    Optional<ExtInventoryAvailabilityReplica> findByStockItemIdAndLocationId(String stockItemId, UUID locationId);

    /**
     * Newest fact this replica holds, for the staleness signal. Empty when no availability fact has
     * ever been applied — a distinct condition from "old", and reported as such.
     */
    @Query("select max(a.updatedAt) from ExtInventoryAvailabilityReplica a")
    Optional<Instant> findNewestUpdatedAt();
}
