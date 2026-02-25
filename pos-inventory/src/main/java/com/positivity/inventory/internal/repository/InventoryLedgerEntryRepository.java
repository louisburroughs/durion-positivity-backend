package com.positivity.inventory.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link InventoryLedgerEntry} entities.
 */
@Repository
public interface InventoryLedgerEntryRepository extends JpaRepository<InventoryLedgerEntry, UUID> {

        /**
         * Find all ledger entries for a specific stock item.
         * 
         * @param stockItemId the stock item ID
         * @return list of ledger entries ordered by timestamp
         */
        List<InventoryLedgerEntry> findByStockItemIdOrderByTimestampDesc(String stockItemId);

        /**
         * Find all ledger entries for a specific stock item ordered oldest-to-newest.
         *
         * @param stockItemId the stock item ID
         * @return list of ledger entries ordered by timestamp ascending
         */
        // Issue #48: Service-level location grouping requires deterministic
        // chronological reads.
        List<InventoryLedgerEntry> findByStockItemIdOrderByTimestampAsc(String stockItemId);

        /**
         * Find all ledger entries for a specific stock item at a specific parent
         * location
         * (or any storage location within it), ordered oldest-to-newest.
         *
         * @param stockItemId the stock item ID
         * @param locationId  the location or storage location ID
         * @return list of ledger entries ordered by timestamp ascending
         */
        // Issue: CAP-215 Story #36
        List<InventoryLedgerEntry> findByStockItemIdAndLocationIdOrderByTimestampAsc(String stockItemId,
                        UUID locationId);

        /**
         * Find ledger entry by adjustment ID.
         * 
         * @param adjustmentId the adjustment ID
         * @return optional ledger entry
         */
        Optional<InventoryLedgerEntry> findByAdjustmentId(UUID adjustmentId);

        /**
         * Calculate current on-hand quantity for a stock item by summing only
         * on-hand-affecting ledger entries.
         * 
         * @param stockItemId the stock item ID
         * @return current on-hand quantity
         */
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

        /**
         * Calculate current on-hand quantity for a stock item at a specific location by
         * summing location-scoped, on-hand-affecting ledger entries.
         *
         * @param stockItemId the stock item ID
         * @param locationId  the location bucket for quantity calculations
         * @return current on-hand quantity at the location
         */
        default Integer calculateOnHandQuantityAtLocation(String stockItemId, UUID locationId) {
                return calculateOnHandQuantityAtLocationForEventTypes(stockItemId, locationId,
                                InventoryLedgerEventType.onHandAffectingTypes());
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
}
