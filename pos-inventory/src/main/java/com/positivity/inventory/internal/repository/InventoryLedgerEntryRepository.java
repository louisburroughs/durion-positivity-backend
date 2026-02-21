package com.positivity.inventory.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.positivity.inventory.internal.model.InventoryLedgerEntry;

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
}
