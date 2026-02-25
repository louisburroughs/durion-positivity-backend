package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryLedgerEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link InventoryLedgerEntry} entities.
 */
@Repository
public interface InventoryLedgerEntryRepository extends JpaRepository<InventoryLedgerEntry, UUID> {

    List<InventoryLedgerEntry> findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
            String stockItemId,
            InventoryLedgerEventType eventType,
            String notesFragment);

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
     * Find ledger entry by adjustment ID.
     * 
     * @param adjustmentId the adjustment ID
     * @return optional ledger entry
     */
    Optional<InventoryLedgerEntry> findByAdjustmentId(UUID adjustmentId);

    /**
     * Calculate current on-hand quantity for a stock item by summing all ledger
     * entries.
     * 
     * @param stockItemId the stock item ID
     * @return current on-hand quantity
     */
    @Query("SELECT COALESCE(SUM(e.changeInQuantity), 0) FROM InventoryLedgerEntry e WHERE e.stockItemId = :stockItemId")
    Integer calculateOnHandQuantity(@Param("stockItemId") String stockItemId);

    /**
     * Calculate current on-hand quantity for a stock item at a specific location.
     *
     * @param stockItemId the stock item ID
     * @param locationId the source location ID
     * @param eventTypes event types that affect physical on-hand
     * @return current on-hand quantity for stock item at location
     */
    @Query("""
            SELECT COALESCE(SUM(e.changeInQuantity), 0)
            FROM InventoryLedgerEntry e
            WHERE e.stockItemId = :stockItemId
              AND e.locationId = :locationId
              AND e.eventType IN :eventTypes
            """)
    Integer calculateOnHandQuantityAtLocation(
            @Param("stockItemId") String stockItemId,
            @Param("locationId") String locationId,
            @Param("eventTypes") List<InventoryLedgerEventType> eventTypes);

    /**
     * Calculate on-hand quantity across all SKUs at a specific location.
     *
     * @param locationId destination/source location ID
     * @param eventTypes event types that affect physical on-hand
     * @return current on-hand quantity at location
     */
    @Query("""
            SELECT COALESCE(SUM(e.changeInQuantity), 0)
            FROM InventoryLedgerEntry e
            WHERE e.locationId = :locationId
              AND e.eventType IN :eventTypes
            """)
    Integer calculateOnHandQuantityAtLocation(
            @Param("locationId") String locationId,
            @Param("eventTypes") List<InventoryLedgerEventType> eventTypes);
}
