package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link InventoryLedgerEntry} entities.
 */
@Repository
public interface InventoryLedgerEntryRepository extends JpaRepository<InventoryLedgerEntry, UUID> {

    List<InventoryLedgerEntry> findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
            String stockItemId,
            InventoryLedgerEventType eventType,
            String notesFragment);

    List<InventoryLedgerEntry> findByStockItemIdOrderByTimestampDesc(String stockItemId);

    List<InventoryLedgerEntry> findByStockItemIdOrderByTimestampAsc(String stockItemId);

    List<InventoryLedgerEntry> findByStockItemIdAndLocationIdOrderByTimestampAsc(String stockItemId,
            UUID locationId);

    Optional<InventoryLedgerEntry> findByAdjustmentId(UUID adjustmentId);

    default Integer calculateOnHandQuantity(UUID stockItemId) {
        return calculateOnHandQuantity(stockItemId.toString());
    }

    default Integer calculateOnHandQuantity(String stockItemId) {
        return calculateOnHandQuantityForEventTypes(stockItemId,
                InventoryLedgerEventType.onHandAffectingTypes());
    }

    @Query("""
            SELECT COALESCE(SUM(e.changeInQuantity), 0)
            FROM InventoryLedgerEntry e
            WHERE e.stockItemId = :stockItemId
              AND e.eventType IN :eventTypes
            """)
    Integer calculateOnHandQuantityForEventTypes(@Param("stockItemId") String stockItemId,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes);

    default Integer calculateOnHandQuantityAtLocation(String stockItemId, UUID locationId) {
        return calculateOnHandQuantityAtLocationForEventTypes(stockItemId, locationId,
                InventoryLedgerEventType.onHandAffectingTypes());
    }

    default Integer calculateOnHandQuantityAtLocation(
            String stockItemId,
            UUID locationId,
            Collection<InventoryLedgerEventType> eventTypes) {
        return calculateOnHandQuantityAtLocationForEventTypes(stockItemId, locationId, eventTypes);
    }

    @Query("""
            SELECT COALESCE(SUM(e.changeInQuantity), 0)
            FROM InventoryLedgerEntry e
            WHERE e.stockItemId = :stockItemId
              AND e.locationId = :locationId
              AND e.eventType IN :eventTypes
            """)
    Integer calculateOnHandQuantityAtLocationForEventTypes(@Param("stockItemId") String stockItemId,
            @Param("locationId") UUID locationId,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes);

    @Query("""
            SELECT COALESCE(SUM(e.changeInQuantity), 0)
            FROM InventoryLedgerEntry e
            WHERE e.locationId = :locationId
              AND e.eventType IN :eventTypes
            """)
    Integer calculateOnHandQuantityAtLocation(
            @Param("locationId") UUID locationId,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes);

    @Query("""
            SELECT e.stockItemId AS stockItemId, COALESCE(SUM(e.changeInQuantity), 0) AS onHandQuantity
            FROM InventoryLedgerEntry e
            WHERE e.locationId = :locationId
              AND e.eventType IN :eventTypes
            GROUP BY e.stockItemId
            HAVING COALESCE(SUM(e.changeInQuantity), 0) > 0
            """)
    List<LocationOnHand> findPositiveOnHandByLocation(
            @Param("locationId") UUID locationId,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes);

    interface LocationOnHand {
        String getStockItemId();

        Long getOnHandQuantity();
    }
}
