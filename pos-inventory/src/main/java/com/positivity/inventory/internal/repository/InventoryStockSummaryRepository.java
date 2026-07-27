package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryStockSummary;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
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
 *
 * <p>Since odoo-parity E1 (issue #1038) the table holds per-lot rows next to the
 * lot-agnostic ones. Every pre-E1 read is pinned to {@code lotId IS NULL} so the
 * lot-agnostic row keeps serving availability/rollup/inquiry exactly as before;
 * only {@link #findByLotId} reads the per-lot rows.
 */
public interface InventoryStockSummaryRepository extends JpaRepository<InventoryStockSummary, UUID> {

    /** Maximum number of location ids passed to a single {@code IN} clause. */
    int LOCATION_ID_CHUNK_SIZE = 1000;

    /**
     * Locks one summary row by its full (stockItemId, locationId, lotId) key, treating null
     * params as IS NULL. The single lock entry point of the posting funnel.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
                        SELECT s FROM InventoryStockSummary s
                        WHERE s.stockItemId = :stockItemId
                          AND ((:locationId IS NULL AND s.locationId IS NULL) OR s.locationId = :locationId)
                          AND ((:lotId IS NULL AND s.lotId IS NULL) OR s.lotId = :lotId)
                        """)
    Optional<InventoryStockSummary> findWithLockByKey(
            @Param("stockItemId") String stockItemId, @Param("locationId") UUID locationId, @Param("lotId") UUID lotId);

    /** Non-locking variant of {@link #findWithLockByKey} for existence checks. */
    @Query("""
                        SELECT s FROM InventoryStockSummary s
                        WHERE s.stockItemId = :stockItemId
                          AND ((:locationId IS NULL AND s.locationId IS NULL) OR s.locationId = :locationId)
                          AND ((:lotId IS NULL AND s.lotId IS NULL) OR s.lotId = :lotId)
                        """)
    Optional<InventoryStockSummary> findByKey(
            @Param("stockItemId") String stockItemId, @Param("locationId") UUID locationId, @Param("lotId") UUID lotId);

    /** Lot-agnostic row for one (stockItemId, locationId) — the pre-E1 balance every reader consumes. */
    @Query("""
                        SELECT s FROM InventoryStockSummary s
                        WHERE s.stockItemId = :stockItemId AND s.locationId = :locationId AND s.lotId IS NULL
                        """)
    Optional<InventoryStockSummary> findByStockItemIdAndLocationId(
            @Param("stockItemId") String stockItemId, @Param("locationId") UUID locationId);

    /** Lot-agnostic NULL-location row for one stock item. */
    @Query("""
                        SELECT s FROM InventoryStockSummary s
                        WHERE s.stockItemId = :stockItemId AND s.locationId IS NULL AND s.lotId IS NULL
                        """)
    Optional<InventoryStockSummary> findByStockItemIdAndLocationIdIsNull(@Param("stockItemId") String stockItemId);

    /** Lot-agnostic rows of one stock item across locations. */
    @Query("""
                        SELECT s FROM InventoryStockSummary s
                        WHERE s.stockItemId = :stockItemId AND s.lotId IS NULL
                        """)
    List<InventoryStockSummary> findByStockItemId(@Param("stockItemId") String stockItemId);

    boolean existsByStockItemId(String stockItemId);

    /** Per-lot rows of one lot across locations (odoo-parity E1, issue #1038 lot read API). */
    List<InventoryStockSummary> findByLotId(UUID lotId);

    /**
     * Per-lot rows with positive on-hand for one (stockItemId, locationId) — the FIFO lot
     * suggestion candidates of odoo-parity E2 (issue #1042).
     */
    @Query("""
                        SELECT s FROM InventoryStockSummary s
                        WHERE s.stockItemId = :stockItemId
                          AND s.locationId = :locationId
                          AND s.lotId IS NOT NULL
                          AND s.onHand > 0
                        """)
    List<InventoryStockSummary> findPositivePerLotRows(
            @Param("stockItemId") String stockItemId, @Param("locationId") UUID locationId);

    /**
     * Total stock attributed to one lot across all per-lot rows: on-hand everywhere plus
     * in-transit toward anywhere (odoo-parity E2, issue #1042). Including in-transit keeps the
     * total conserved across transfer dispatch → receive, so a fully-dispatched-but-unreceived
     * lot does not spuriously read as empty for the CONSUMED status transition.
     */
    @Query("""
                        SELECT COALESCE(SUM(s.onHand + s.inTransitQty), 0)
                        FROM InventoryStockSummary s
                        WHERE s.lotId = :lotId
                        """)
    long sumLotStockAcrossLocations(@Param("lotId") UUID lotId);

    /**
     * Σ per-lot on-hand of the SKU's EXPIRED, ACTIVE lots at the given scope (odoo-parity E3,
     * issue #1047; spec §6 E3, decision D-7 on-read guard): the quantity to subtract from the
     * lot-agnostic ATP so expired lots drop out of availability while staying in on-hand. A null
     * {@code locationId} sums across every location. Only ACTIVE lots are deducted (a
     * QUARANTINED/RECALLED lot is already blocked at the outbound/suggestion boundary), matching
     * the daily {@code LotExpiryScheduler}'s selection.
     */
    @Query(value = """
                    SELECT COALESCE(SUM(s.on_hand), 0)
                    FROM inventory_stock_summary s
                    JOIN inventory_lot l ON l.lot_id = s.lot_id
                    WHERE s.stock_item_id = :stockItemId
                      AND s.lot_id IS NOT NULL
                      AND s.on_hand > 0
                      AND (CAST(:locationId AS uuid) IS NULL OR s.location_id = :locationId)
                      AND l.status = 'ACTIVE'
                      AND l.expiration_date IS NOT NULL
                      AND l.expiration_date < :today
                    """, nativeQuery = true)
    long sumExpiredActiveLotOnHand(
            @Param("stockItemId") String stockItemId,
            @Param("locationId") UUID locationId,
            @Param("today") LocalDate today);

    /**
     * Earliest lot expiry per location for the SKU over its ACTIVE, non-expired, positive-on-hand
     * per-lot rows (odoo-parity E3, issue #1047): the data behind the FEFO sourcing strategy's
     * per-location ordering. Locations with no such lot are absent from the result.
     */
    @Query(value = """
                    SELECT s.location_id AS locationId, MIN(l.expiration_date) AS earliestExpiry
                    FROM inventory_stock_summary s
                    JOIN inventory_lot l ON l.lot_id = s.lot_id
                    WHERE s.stock_item_id = :stockItemId
                      AND s.lot_id IS NOT NULL
                      AND s.on_hand > 0
                      AND s.location_id IN (:locationIds)
                      AND l.status = 'ACTIVE'
                      AND l.expiration_date IS NOT NULL
                      AND l.expiration_date >= :today
                    GROUP BY s.location_id
                    """, nativeQuery = true)
    List<LocationExpiry> earliestExpiryByLocation(
            @Param("stockItemId") String stockItemId,
            @Param("locationIds") Collection<UUID> locationIds,
            @Param("today") LocalDate today);

    /** Whether the SKU has any ACTIVE, non-expired lot carrying an expiration date. */
    @Query(value = """
                    SELECT CASE WHEN COUNT(*) > 0 THEN true ELSE false END
                    FROM inventory_stock_summary s
                    JOIN inventory_lot l ON l.lot_id = s.lot_id
                    WHERE s.stock_item_id = :stockItemId
                      AND s.lot_id IS NOT NULL
                      AND s.on_hand > 0
                      AND l.status = 'ACTIVE'
                      AND l.expiration_date IS NOT NULL
                      AND l.expiration_date >= :today
                    """, nativeQuery = true)
    boolean hasExpiryData(@Param("stockItemId") String stockItemId, @Param("today") LocalDate today);

    /** One location's earliest lot expiry (native projection for the FEFO provider). */
    interface LocationExpiry {
        UUID getLocationId();

        LocalDate getEarliestExpiry();
    }

    /** Lot-agnostic rows with positive on-hand at one location. */
    @Query("""
                        SELECT s FROM InventoryStockSummary s
                        WHERE s.locationId = :locationId AND s.onHand > :onHand AND s.lotId IS NULL
                        """)
    List<InventoryStockSummary> findByLocationIdAndOnHandGreaterThan(
            @Param("locationId") UUID locationId, @Param("onHand") long onHand);

    @Query("""
                        SELECT COALESCE(SUM(s.onHand), 0)
                        FROM InventoryStockSummary s
                        WHERE s.locationId = :locationId AND s.lotId IS NULL
                        """)
    long sumOnHandAtLocation(@Param("locationId") UUID locationId);

    @Query("""
                        SELECT COALESCE(SUM(s.allocated), 0)
                        FROM InventoryStockSummary s
                        WHERE s.locationId = :locationId AND s.lotId IS NULL
                        """)
    long sumAllocatedAtLocation(@Param("locationId") UUID locationId);

    @Query("""
                        SELECT s.locationId AS locationId, COALESCE(SUM(s.onHand), 0) AS onHand,
                               COALESCE(SUM(s.allocated), 0) AS allocated
                        FROM InventoryStockSummary s
                        WHERE s.locationId IN :locationIds AND s.lotId IS NULL
                        GROUP BY s.locationId
                        """)
    List<LocationSummary> sumByLocation(@Param("locationIds") Collection<UUID> locationIds);

    @Query("""
                        SELECT s.locationId AS locationId, COALESCE(SUM(s.onHand), 0) AS onHand,
                               COALESCE(SUM(s.allocated), 0) AS allocated
                        FROM InventoryStockSummary s
                        WHERE s.stockItemId = :stockItemId
                          AND s.locationId IN :locationIds
                          AND s.lotId IS NULL
                        GROUP BY s.locationId
                        """)
    List<LocationSummary> sumByLocationForSku(
            @Param("stockItemId") String stockItemId, @Param("locationIds") Collection<UUID> locationIds);

    interface LocationSummary {
        UUID getLocationId();

        long getOnHand();

        long getAllocated();
    }

    // ─── Valuation read model on-hand aggregation (odoo-parity J2, issue #1052) ──
    // Per-SKU on-hand over the lot-agnostic rows (lotId IS NULL) — the authoritative
    // balances every reader consumes. Non-zero keys only; the valuation service pairs
    // each with the SKU's current unit cost. Value is per-SKU (cost is per-SKU), so
    // on-hand is summed across locations unless a site filter is applied.

    /** Per-SKU on-hand summed across all sites (lot-agnostic rows), non-zero keys only. */
    @Query("""
                        SELECT s.stockItemId AS stockItemId, COALESCE(SUM(s.onHand), 0) AS onHand
                        FROM InventoryStockSummary s
                        WHERE s.lotId IS NULL
                        GROUP BY s.stockItemId
                        HAVING COALESCE(SUM(s.onHand), 0) <> 0
                        """)
    List<SkuOnHand> sumOnHandBySku();

    /** Per-SKU on-hand at one site (lot-agnostic rows), non-zero keys only. */
    @Query("""
                        SELECT s.stockItemId AS stockItemId, COALESCE(SUM(s.onHand), 0) AS onHand
                        FROM InventoryStockSummary s
                        WHERE s.locationId = :locationId AND s.lotId IS NULL
                        GROUP BY s.stockItemId
                        HAVING COALESCE(SUM(s.onHand), 0) <> 0
                        """)
    List<SkuOnHand> sumOnHandBySkuAtLocation(@Param("locationId") UUID locationId);

    /** On-hand of one SKU summed across all sites (lot-agnostic rows). */
    @Query("""
                        SELECT COALESCE(SUM(s.onHand), 0)
                        FROM InventoryStockSummary s
                        WHERE s.stockItemId = :stockItemId AND s.lotId IS NULL
                        """)
    long sumOnHandForSku(@Param("stockItemId") String stockItemId);

    interface SkuOnHand {
        String getStockItemId();

        long getOnHand();
    }

    /**
     * Set-based drift diff between ledger truth and the summary table (issue
     * #1024 drift verifier): returns ONLY mismatching keys, so the scheduled
     * job never materializes either table in application memory. Emulates a
     * full outer join as a UNION of two left joins (H2-compatible). Event
     * types are passed as enum names ({@code event_type} is stored as text).
     *
     * <p>{@code in_transit_qty} reconstruction (odoo-parity C2, #1036) uses
     * the same ledger-shape rule as the posting funnel: {@code TRANSFER_OUT}
     * contributes {@code -change} (positive) at its DESTINATION
     * ({@code to_location_id}) key, while {@code TRANSFER_IN} contributes
     * {@code -change} (negative) at its own key.
     *
     * <p>Per-lot keys (odoo-parity E1, #1038) mirror the funnel's dual-row
     * rule: every entry contributes to its lot-agnostic (NULL {@code lot_id})
     * key, and a lot-tagged entry ADDITIONALLY contributes the same deltas to
     * its (stock_item_id, location_id, lot_id) key — hence the doubled
     * contribution branches filtered on {@code lot_id IS NOT NULL}.
     */
    @Query(value = """
                        WITH contributions AS (
                            SELECT stock_item_id,
                                   location_id,
                                   CAST(NULL AS uuid) AS lot_id,
                                   CASE WHEN event_type IN (:onHandTypes) THEN change_in_quantity ELSE 0 END AS on_hand_c,
                                   CASE WHEN event_type = :allocationCreated THEN change_in_quantity
                                        WHEN event_type = :allocationReleased THEN -change_in_quantity
                                        ELSE 0 END AS allocated_c,
                                   CASE WHEN event_type = :reservationCreated THEN change_in_quantity
                                        WHEN event_type = :reservationReleased THEN -change_in_quantity
                                        ELSE 0 END AS reserved_c,
                                   CASE WHEN event_type = :transferIn THEN -change_in_quantity ELSE 0 END AS in_transit_c
                            FROM inventory_ledger_entry
                            WHERE event_type IN (:summaryTypes)
                            UNION ALL
                            SELECT stock_item_id,
                                   location_id,
                                   lot_id,
                                   CASE WHEN event_type IN (:onHandTypes) THEN change_in_quantity ELSE 0 END,
                                   CASE WHEN event_type = :allocationCreated THEN change_in_quantity
                                        WHEN event_type = :allocationReleased THEN -change_in_quantity
                                        ELSE 0 END,
                                   CASE WHEN event_type = :reservationCreated THEN change_in_quantity
                                        WHEN event_type = :reservationReleased THEN -change_in_quantity
                                        ELSE 0 END,
                                   CASE WHEN event_type = :transferIn THEN -change_in_quantity ELSE 0 END
                            FROM inventory_ledger_entry
                            WHERE event_type IN (:summaryTypes) AND lot_id IS NOT NULL
                            UNION ALL
                            SELECT stock_item_id,
                                   to_location_id,
                                   CAST(NULL AS uuid),
                                   0, 0, 0,
                                   -change_in_quantity
                            FROM inventory_ledger_entry
                            WHERE event_type = :transferOut AND to_location_id IS NOT NULL
                            UNION ALL
                            SELECT stock_item_id,
                                   to_location_id,
                                   lot_id,
                                   0, 0, 0,
                                   -change_in_quantity
                            FROM inventory_ledger_entry
                            WHERE event_type = :transferOut AND to_location_id IS NOT NULL AND lot_id IS NOT NULL
                        ), ledger AS (
                            SELECT stock_item_id,
                                   location_id,
                                   lot_id,
                                   COALESCE(SUM(on_hand_c), 0) AS on_hand,
                                   COALESCE(SUM(allocated_c), 0) AS allocated,
                                   COALESCE(SUM(reserved_c), 0) AS reserved,
                                   COALESCE(SUM(in_transit_c), 0) AS in_transit
                            FROM contributions
                            GROUP BY stock_item_id, location_id, lot_id
                        )
                        SELECT l.stock_item_id AS stockItemId,
                               CAST(l.location_id AS VARCHAR) AS locationId,
                               CAST(l.lot_id AS VARCHAR) AS lotId,
                               l.on_hand AS ledgerOnHand,
                               l.allocated AS ledgerAllocated,
                               l.reserved AS ledgerReserved,
                               l.in_transit AS ledgerInTransit,
                               s.on_hand AS summaryOnHand,
                               s.allocated AS summaryAllocated,
                               s.reserved AS summaryReserved,
                               s.in_transit_qty AS summaryInTransit
                        FROM ledger l
                        LEFT JOIN inventory_stock_summary s
                          ON s.stock_item_id = l.stock_item_id
                         AND s.location_id IS NOT DISTINCT FROM l.location_id
                         AND s.lot_id IS NOT DISTINCT FROM l.lot_id
                        WHERE (s.summary_id IS NULL
                               AND (l.on_hand <> 0 OR l.allocated <> 0 OR l.reserved <> 0 OR l.in_transit <> 0))
                           OR (s.summary_id IS NOT NULL
                               AND (s.on_hand <> l.on_hand
                                    OR s.allocated <> l.allocated
                                    OR s.reserved <> l.reserved
                                    OR s.in_transit_qty <> l.in_transit
                                    OR s.atp <> l.on_hand - l.allocated))
                        UNION ALL
                        SELECT s.stock_item_id AS stockItemId,
                               CAST(s.location_id AS VARCHAR) AS locationId,
                               CAST(s.lot_id AS VARCHAR) AS lotId,
                               CAST(0 AS BIGINT) AS ledgerOnHand,
                               CAST(0 AS BIGINT) AS ledgerAllocated,
                               CAST(0 AS BIGINT) AS ledgerReserved,
                               CAST(0 AS BIGINT) AS ledgerInTransit,
                               s.on_hand AS summaryOnHand,
                               s.allocated AS summaryAllocated,
                               s.reserved AS summaryReserved,
                               s.in_transit_qty AS summaryInTransit
                        FROM inventory_stock_summary s
                        LEFT JOIN ledger l
                          ON l.stock_item_id = s.stock_item_id
                         AND l.location_id IS NOT DISTINCT FROM s.location_id
                         AND l.lot_id IS NOT DISTINCT FROM s.lot_id
                        WHERE l.stock_item_id IS NULL
                          AND (s.on_hand <> 0 OR s.allocated <> 0 OR s.reserved <> 0 OR s.atp <> 0
                               OR s.in_transit_qty <> 0)
                        """, nativeQuery = true)
    List<DriftRow> findDriftRows(
            @Param("onHandTypes") Collection<String> onHandTypes,
            @Param("allocationCreated") String allocationCreated,
            @Param("allocationReleased") String allocationReleased,
            @Param("reservationCreated") String reservationCreated,
            @Param("reservationReleased") String reservationReleased,
            @Param("transferIn") String transferIn,
            @Param("transferOut") String transferOut,
            @Param("summaryTypes") Collection<String> summaryTypes);

    interface DriftRow {
        String getStockItemId();

        /** Rendered as text in SQL (log-only field; H2 returns raw UUID columns as byte[] in native projections). */
        String getLocationId();

        /** Rendered as text in SQL; null on lot-agnostic keys (log-only field). */
        String getLotId();

        long getLedgerOnHand();

        long getLedgerAllocated();

        long getLedgerReserved();

        long getLedgerInTransit();

        Long getSummaryOnHand();

        Long getSummaryAllocated();

        Long getSummaryReserved();

        Long getSummaryInTransit();
    }

    /**
     * Set-based per-location reconciliation of the summary {@code allocated}
     * column against ledger outstanding allocations (odoo-parity K3, issue
     * #1032): {@code Σ ALLOCATION_CREATED − Σ ALLOCATION_RELEASED} per
     * location must equal {@code SUM(allocated)} over the location's summary
     * rows. Returns ONLY mismatching locations — same emulated full outer
     * join as {@link #findDriftRows} (H2-compatible; the anti-join test uses
     * {@code l.outstanding IS NULL} because {@code location_id} is nullable
     * on both sides). Event types are passed as enum names. Restricted to the
     * lot-agnostic rows ({@code lot_id IS NULL}) — they alone carry the
     * authoritative allocation balances (odoo-parity E1, #1038).
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
                            WHERE lot_id IS NULL
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
