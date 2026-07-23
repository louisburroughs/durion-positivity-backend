package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryStockSummary;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link InventoryStockSummary} rows (issue #1024, A1).
 *
 * <p>Writes go exclusively through {@code LedgerPostingService} (row-locked
 * upsert) and {@code StockSummaryRebuildService}; everything else is read-only.
 */
public interface InventoryStockSummaryRepository extends JpaRepository<InventoryStockSummary, UUID> {

    /** Maximum number of location ids passed to a single {@code IN} clause. */
    int LOCATION_ID_CHUNK_SIZE = 1000;

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InventoryStockSummary s WHERE s.stockItemId = :stockItemId AND s.locationId = :locationId")
    Optional<InventoryStockSummary> findWithLockByStockItemIdAndLocationId(
            @Param("stockItemId") String stockItemId, @Param("locationId") UUID locationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InventoryStockSummary s WHERE s.stockItemId = :stockItemId AND s.locationId IS NULL")
    Optional<InventoryStockSummary> findWithLockByStockItemIdAndLocationIdIsNull(
            @Param("stockItemId") String stockItemId);

    Optional<InventoryStockSummary> findByStockItemIdAndLocationId(String stockItemId, UUID locationId);

    Optional<InventoryStockSummary> findByStockItemIdAndLocationIdIsNull(String stockItemId);

    List<InventoryStockSummary> findByStockItemId(String stockItemId);

    boolean existsByStockItemId(String stockItemId);

    List<InventoryStockSummary> findByLocationIdAndOnHandGreaterThan(UUID locationId, long onHand);

    @Query("""
                        SELECT COALESCE(SUM(s.onHand), 0)
                        FROM InventoryStockSummary s
                        WHERE s.locationId = :locationId
                        """)
    long sumOnHandAtLocation(@Param("locationId") UUID locationId);

    @Query("""
                        SELECT COALESCE(SUM(s.allocated), 0)
                        FROM InventoryStockSummary s
                        WHERE s.locationId = :locationId
                        """)
    long sumAllocatedAtLocation(@Param("locationId") UUID locationId);

    @Query("""
                        SELECT s.locationId AS locationId, COALESCE(SUM(s.onHand), 0) AS onHand,
                               COALESCE(SUM(s.allocated), 0) AS allocated
                        FROM InventoryStockSummary s
                        WHERE s.locationId IN :locationIds
                        GROUP BY s.locationId
                        """)
    List<LocationSummary> sumByLocation(@Param("locationIds") Collection<UUID> locationIds);

    @Query("""
                        SELECT s.locationId AS locationId, COALESCE(SUM(s.onHand), 0) AS onHand,
                               COALESCE(SUM(s.allocated), 0) AS allocated
                        FROM InventoryStockSummary s
                        WHERE s.stockItemId = :stockItemId
                          AND s.locationId IN :locationIds
                        GROUP BY s.locationId
                        """)
    List<LocationSummary> sumByLocationForSku(
            @Param("stockItemId") String stockItemId, @Param("locationIds") Collection<UUID> locationIds);

    interface LocationSummary {
        UUID getLocationId();

        long getOnHand();

        long getAllocated();
    }

    /**
     * Set-based drift diff between ledger truth and the summary table (issue
     * #1024 drift verifier): returns ONLY mismatching keys, so the scheduled
     * job never materializes either table in application memory. Emulates a
     * full outer join as a UNION of two left joins (H2-compatible). Event
     * types are passed as enum names ({@code event_type} is stored as text).
     */
    @Query(value = """
                        WITH ledger AS (
                            SELECT stock_item_id,
                                   location_id,
                                   COALESCE(SUM(CASE WHEN event_type IN (:onHandTypes) THEN change_in_quantity ELSE 0 END), 0) AS on_hand,
                                   COALESCE(SUM(CASE WHEN event_type = :allocationCreated THEN change_in_quantity
                                                     WHEN event_type = :allocationReleased THEN -change_in_quantity
                                                     ELSE 0 END), 0) AS allocated,
                                   COALESCE(SUM(CASE WHEN event_type = :reservationCreated THEN change_in_quantity
                                                     WHEN event_type = :reservationReleased THEN -change_in_quantity
                                                     ELSE 0 END), 0) AS reserved
                            FROM inventory_ledger_entry
                            WHERE event_type IN (:summaryTypes)
                            GROUP BY stock_item_id, location_id
                        )
                        SELECT l.stock_item_id AS stockItemId,
                               CAST(l.location_id AS VARCHAR) AS locationId,
                               l.on_hand AS ledgerOnHand,
                               l.allocated AS ledgerAllocated,
                               l.reserved AS ledgerReserved,
                               s.on_hand AS summaryOnHand,
                               s.allocated AS summaryAllocated,
                               s.reserved AS summaryReserved
                        FROM ledger l
                        LEFT JOIN inventory_stock_summary s
                          ON s.stock_item_id = l.stock_item_id
                         AND s.location_id IS NOT DISTINCT FROM l.location_id
                        WHERE (s.summary_id IS NULL
                               AND (l.on_hand <> 0 OR l.allocated <> 0 OR l.reserved <> 0))
                           OR (s.summary_id IS NOT NULL
                               AND (s.on_hand <> l.on_hand
                                    OR s.allocated <> l.allocated
                                    OR s.reserved <> l.reserved
                                    OR s.atp <> l.on_hand - l.allocated))
                        UNION ALL
                        SELECT s.stock_item_id AS stockItemId,
                               CAST(s.location_id AS VARCHAR) AS locationId,
                               CAST(0 AS BIGINT) AS ledgerOnHand,
                               CAST(0 AS BIGINT) AS ledgerAllocated,
                               CAST(0 AS BIGINT) AS ledgerReserved,
                               s.on_hand AS summaryOnHand,
                               s.allocated AS summaryAllocated,
                               s.reserved AS summaryReserved
                        FROM inventory_stock_summary s
                        LEFT JOIN ledger l
                          ON l.stock_item_id = s.stock_item_id
                         AND l.location_id IS NOT DISTINCT FROM s.location_id
                        WHERE l.stock_item_id IS NULL
                          AND (s.on_hand <> 0 OR s.allocated <> 0 OR s.reserved <> 0 OR s.atp <> 0)
                        """, nativeQuery = true)
    List<DriftRow> findDriftRows(
            @Param("onHandTypes") Collection<String> onHandTypes,
            @Param("allocationCreated") String allocationCreated,
            @Param("allocationReleased") String allocationReleased,
            @Param("reservationCreated") String reservationCreated,
            @Param("reservationReleased") String reservationReleased,
            @Param("summaryTypes") Collection<String> summaryTypes);

    interface DriftRow {
        String getStockItemId();

        /** Rendered as text in SQL (log-only field; H2 returns raw UUID columns as byte[] in native projections). */
        String getLocationId();

        long getLedgerOnHand();

        long getLedgerAllocated();

        long getLedgerReserved();

        Long getSummaryOnHand();

        Long getSummaryAllocated();

        Long getSummaryReserved();
    }

    /**
     * Set-based per-location reconciliation of the summary {@code allocated}
     * column against ledger outstanding allocations (odoo-parity K3, issue
     * #1032): {@code Σ ALLOCATION_CREATED − Σ ALLOCATION_RELEASED} per
     * location must equal {@code SUM(allocated)} over the location's summary
     * rows. Returns ONLY mismatching locations — same emulated full outer
     * join as {@link #findDriftRows} (H2-compatible; the anti-join test uses
     * {@code l.outstanding IS NULL} because {@code location_id} is nullable
     * on both sides). Event types are passed as enum names.
     */
    @Query(value = """
                        WITH ledger AS (
                            SELECT location_id,
                                   COALESCE(SUM(CASE WHEN event_type = :created THEN change_in_quantity
                                                     WHEN event_type = :released THEN -change_in_quantity
                                                     ELSE 0 END), 0) AS outstanding
                            FROM inventory_ledger_entry
                            WHERE event_type IN (:created, :released)
                            GROUP BY location_id
                        ), summary AS (
                            SELECT location_id, COALESCE(SUM(allocated), 0) AS allocated
                            FROM inventory_stock_summary
                            GROUP BY location_id
                        )
                        SELECT CAST(l.location_id AS VARCHAR) AS locationId,
                               l.outstanding AS ledgerOutstanding,
                               COALESCE(s.allocated, 0) AS summaryAllocated
                        FROM ledger l
                        LEFT JOIN summary s ON s.location_id IS NOT DISTINCT FROM l.location_id
                        WHERE COALESCE(s.allocated, 0) <> l.outstanding
                        UNION ALL
                        SELECT CAST(s.location_id AS VARCHAR) AS locationId,
                               CAST(0 AS BIGINT) AS ledgerOutstanding,
                               s.allocated AS summaryAllocated
                        FROM summary s
                        LEFT JOIN ledger l ON l.location_id IS NOT DISTINCT FROM s.location_id
                        WHERE l.outstanding IS NULL AND s.allocated <> 0
                        """, nativeQuery = true)
    List<AllocatedDriftRow> findAllocatedDriftByLocation(
            @Param("created") String created, @Param("released") String released);

    /** One location whose summary {@code allocated} disagrees with ledger outstanding. Log-only projection. */
    interface AllocatedDriftRow {
        /** Rendered as text in SQL; null for the null-location (site-level) key. */
        String getLocationId();

        long getLedgerOutstanding();

        long getSummaryAllocated();
    }

    /**
     * Chunk-safe per-location on-hand and allocated sums for the given
     * locations, optionally restricted to one stock item. Locations without
     * summary rows are absent from the maps (treat as 0).
     */
    default LocationQuantityMaps sumByLocationChunked(Collection<UUID> locationIds, String stockItemIdOrNull) {
        Map<UUID, Long> onHand = new HashMap<>();
        Map<UUID, Long> allocated = new HashMap<>();
        if (locationIds != null && !locationIds.isEmpty()) {
            List<UUID> distinctIds = locationIds.stream().distinct().toList();
            for (int start = 0; start < distinctIds.size(); start += LOCATION_ID_CHUNK_SIZE) {
                List<UUID> chunk =
                        distinctIds.subList(start, Math.min(start + LOCATION_ID_CHUNK_SIZE, distinctIds.size()));
                List<LocationSummary> rows = stockItemIdOrNull == null
                        ? sumByLocation(chunk)
                        : sumByLocationForSku(stockItemIdOrNull, chunk);
                for (LocationSummary row : rows) {
                    onHand.merge(row.getLocationId(), row.getOnHand(), Long::sum);
                    allocated.merge(row.getLocationId(), row.getAllocated(), Long::sum);
                }
            }
        }
        return new LocationQuantityMaps(Map.copyOf(onHand), Map.copyOf(allocated));
    }

    record LocationQuantityMaps(Map<UUID, Long> onHand, Map<UUID, Long> allocated) {}
}
