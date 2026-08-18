package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtInventoryAvailability;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Read side of the pos-inventory-owned availability replica (CAP #1315). */
@Repository
public interface ExtInventoryAvailabilityRepository extends JpaRepository<ExtInventoryAvailability, UUID> {

    Optional<ExtInventoryAvailability> findByStockItemIdAndLocationId(String stockItemId, UUID locationId);

    /**
     * Newest fact this replica holds, for the staleness signal. Empty when no availability fact has
     * ever been applied — which is a distinct condition from "old", and reported as such.
     */
    @Query("select max(a.updatedAt) from ExtInventoryAvailability a")
    Optional<Instant> findNewestUpdatedAt();
}
