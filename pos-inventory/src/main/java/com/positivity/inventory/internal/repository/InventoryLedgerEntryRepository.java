package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link InventoryLedgerEntry} entities.
 *
 * <p>Since issue #1024 (A1) the availability/inquiry/rollup read paths are
 * served by the {@code inventory_stock_summary} read model, not by the
 * aggregate queries below. The {@code calculateOnHand*}/{@code sumQuantity*}
 * aggregations remain for (a) posting-path {@code quantityAfter} and
 * validation computations and (b) the summary rebuild/drift-verifier jobs,
 * for which the ledger is the source of truth. Do not reintroduce them on
 * hot read endpoints.
 */
public interface InventoryLedgerEntryRepository
        extends JpaRepository<InventoryLedgerEntry, UUID>, JpaSpecificationExecutor<InventoryLedgerEntry> {

    List<InventoryLedgerEntry> findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
            String stockItemId, InventoryLedgerEventType eventType, String notesFragment);

    /** Per-line idempotency guard for event-driven postings (order parity story H2, #1079). */
    boolean existsByEventTypeAndSourceTransactionId(InventoryLedgerEventType eventType, String sourceTransactionId);

    /**
     * Full movement chain of one lot, oldest first (odoo-parity E5, issue #1051): every ledger
     * entry the posting funnel stamped with this {@code lotId} — the receipt, putaway, picks,
     * consumption, returns, scraps, and transfers — chronologically ordered, ledger id breaking
     * timestamp ties deterministically. The read basis for lot traceability.
     */
    List<InventoryLedgerEntry> findByLotIdOrderByTimestampAscLedgerEntryIdAsc(UUID lotId);

    List<InventoryLedgerEntry> findByStockItemIdOrderByTimestampDesc(String stockItemId);

    List<InventoryLedgerEntry> findByStockItemIdOrderByTimestampAsc(String stockItemId);

    List<InventoryLedgerEntry> findByStockItemIdAndLocationIdOrderByTimestampAsc(String stockItemId, UUID locationId);

    List<InventoryLedgerEntry> findByLocationIdAndEventTypeIn(
            UUID locationId, Collection<InventoryLedgerEventType> eventTypes);

    Optional<InventoryLedgerEntry> findByAdjustmentId(UUID adjustmentId);

    // ─── Cycle-count conflict window queries (odoo-parity I2, issue #1026) ────

    /**
     * Interfering movements for a cycle-count task: on-hand-affecting entries
     * for the task's SKU recorded after the task snapshot was taken.
     */
    List<InventoryLedgerEntry> findByStockItemIdAndEventTypeInAndTimestampGreaterThanOrderByTimestampAsc(
            String stockItemId, Collection<InventoryLedgerEventType> eventTypes, java.time.Instant after);

    /** Location-scoped variant of the interfering-movement listing. */
    List<InventoryLedgerEntry> findByStockItemIdAndLocationIdAndEventTypeInAndTimestampGreaterThanOrderByTimestampAsc(
            String stockItemId,
            UUID locationId,
            Collection<InventoryLedgerEventType> eventTypes,
            java.time.Instant after);

    /**
     * Net on-hand delta for one SKU since a point in time — non-zero means
     * movements interfered with a count window (odoo-parity I2).
     */
    @Query("""
                        SELECT COALESCE(SUM(e.changeInQuantity), 0)
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND e.eventType IN :eventTypes
                          AND e.timestamp > :after
                        """)
    Integer sumChangeForStockItemSince(
            @Param("stockItemId") String stockItemId,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes,
            @Param("after") java.time.Instant after);

    /** Location-scoped variant of {@link #sumChangeForStockItemSince}. */
    @Query("""
                        SELECT COALESCE(SUM(e.changeInQuantity), 0)
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND e.locationId = :locationId
                          AND e.eventType IN :eventTypes
                          AND e.timestamp > :after
                        """)
    Integer sumChangeForStockItemAtLocationSince(
            @Param("stockItemId") String stockItemId,
            @Param("locationId") UUID locationId,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes,
            @Param("after") java.time.Instant after);

    default Integer calculateOnHandQuantity(UUID stockItemId) {
        return calculateOnHandQuantityForEventTypes(
                stockItemId.toString(), InventoryLedgerEventType.onHandAffectingTypes());
    }

    @Query("""
                        SELECT COALESCE(SUM(e.changeInQuantity), 0)
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND e.eventType IN :eventTypes
                        """)
    Integer calculateOnHandQuantityForEventTypes(
            @Param("stockItemId") String stockItemId,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes);

    default Integer calculateOnHandQuantityAtLocation(UUID stockItemId, UUID locationId) {
        return calculateOnHandQuantityAtLocationForEventTypes(
                stockItemId.toString(), locationId, InventoryLedgerEventType.onHandAffectingTypes());
    }

    default Integer calculateOnHandQuantityAtLocation(
            UUID stockItemId, UUID locationId, Collection<InventoryLedgerEventType> eventTypes) {
        return calculateOnHandQuantityAtLocationForEventTypes(stockItemId.toString(), locationId, eventTypes);
    }

    default Integer calculateOnHandQuantityAtLocation(String stockItemId, UUID locationId) {
        return calculateOnHandQuantityAtLocationForEventTypes(
                stockItemId, locationId, InventoryLedgerEventType.onHandAffectingTypes());
    }

    default Integer calculateOnHandQuantityAtLocation(
            String stockItemId, UUID locationId, Collection<InventoryLedgerEventType> eventTypes) {
        return calculateOnHandQuantityAtLocationForEventTypes(stockItemId, locationId, eventTypes);
    }

    @Query("""
                        SELECT COALESCE(SUM(e.changeInQuantity), 0)
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND e.locationId = :locationId
                          AND e.eventType IN :eventTypes
                        """)
    Integer calculateOnHandQuantityAtLocationForEventTypes(
            @Param("stockItemId") String stockItemId,
            @Param("locationId") UUID locationId,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes);

    @Query("""
                        SELECT COALESCE(SUM(e.changeInQuantity), 0)
                        FROM InventoryLedgerEntry e
                        WHERE e.locationId = :locationId
                          AND e.eventType IN :eventTypes
                        """)
    Integer calculateOnHandQuantityAtLocation(
            @Param("locationId") UUID locationId, @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes);

    @Query("""
                        SELECT e.stockItemId AS stockItemId, COALESCE(SUM(e.changeInQuantity), 0) AS onHandQuantity
                        FROM InventoryLedgerEntry e
                        WHERE e.locationId = :locationId
                          AND e.eventType IN :eventTypes
                        GROUP BY e.stockItemId
                        HAVING COALESCE(SUM(e.changeInQuantity), 0) > 0
                        """)
    List<LocationOnHand> findPositiveOnHandByLocation(
            @Param("locationId") UUID locationId, @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes);

    interface LocationOnHand {
        String getStockItemId();

        Long getOnHandQuantity();
    }

    // ─── Point-in-time (as-of) aggregations (odoo-parity A3, issue #1029) ─────
    // Direct ledger math with a timestamp bound — the pre-A1 SUM queries with an
    // `e.timestamp <= :asOf` cutoff. Never touches the stock summary; acceptable
    // for audit-frequency historical queries only.

    /** Per-location on-hand for one stock item as of an instant (timestamp inclusive). */
    @Query("""
                        SELECT e.locationId AS locationId, COALESCE(SUM(e.changeInQuantity), 0) AS quantity
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND e.eventType IN :eventTypes
                          AND e.timestamp <= :asOf
                        GROUP BY e.locationId
                        """)
    List<LocationQuantity> sumQuantityByLocationForStockItemAsOf(
            @Param("stockItemId") String stockItemId,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes,
            @Param("asOf") java.time.Instant asOf);

    /** Total on-hand at one location (all stock items) as of an instant. */
    @Query("""
                        SELECT COALESCE(SUM(e.changeInQuantity), 0)
                        FROM InventoryLedgerEntry e
                        WHERE e.locationId = :locationId
                          AND e.eventType IN :eventTypes
                          AND e.timestamp <= :asOf
                        """)
    Integer calculateOnHandAtLocationAsOf(
            @Param("locationId") UUID locationId,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes,
            @Param("asOf") java.time.Instant asOf);

    /** On-hand of one stock item at one location as of an instant. */
    @Query("""
                        SELECT COALESCE(SUM(e.changeInQuantity), 0)
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND e.locationId = :locationId
                          AND e.eventType IN :eventTypes
                          AND e.timestamp <= :asOf
                        """)
    Integer calculateOnHandForStockItemAtLocationAsOf(
            @Param("stockItemId") String stockItemId,
            @Param("locationId") UUID locationId,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes,
            @Param("asOf") java.time.Instant asOf);

    /** Positive per-stock-item on-hand at one location as of an instant. */
    @Query("""
                        SELECT e.stockItemId AS stockItemId, COALESCE(SUM(e.changeInQuantity), 0) AS onHandQuantity
                        FROM InventoryLedgerEntry e
                        WHERE e.locationId = :locationId
                          AND e.eventType IN :eventTypes
                          AND e.timestamp <= :asOf
                        GROUP BY e.stockItemId
                        HAVING COALESCE(SUM(e.changeInQuantity), 0) > 0
                        """)
    List<LocationOnHand> findPositiveOnHandByLocationAsOf(
            @Param("locationId") UUID locationId,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes,
            @Param("asOf") java.time.Instant asOf);

    /**
     * Sum of {@code changeInQuantity} for one event type attributed to a source
     * transaction (allocation ledger events carry the allocation id as
     * {@code sourceTransactionId}). Used to derive how much of an allocation
     * has already been released (CAP-218 #662).
     */
    @Query("""
                        SELECT COALESCE(SUM(e.changeInQuantity), 0)
                        FROM InventoryLedgerEntry e
                        WHERE e.sourceTransactionId = :sourceTransactionId
                          AND e.eventType = :eventType
                        """)
    int sumChangeBySourceTransactionIdAndEventType(
            @Param("sourceTransactionId") String sourceTransactionId,
            @Param("eventType") InventoryLedgerEventType eventType);

    /**
     * Earliest receipt timestamp of one SKU per location (odoo-parity H1,
     * issue #1037): {@code MIN(timestamp)} over {@code GOODS_RECEIPT}/
     * {@code TRANSFER_IN} entries per location. FIFO sourcing orders
     * candidate locations by this value — a documented approximation
     * (per-location earliest receipt, not per-unit aging). Locations with no
     * receipt entries are absent from the result (FIFO sorts them last).
     */
    @Query("""
                        SELECT e.locationId AS locationId, MIN(e.timestamp) AS earliestReceipt
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND e.locationId IN :locationIds
                          AND e.eventType IN :eventTypes
                        GROUP BY e.locationId
                        """)
    List<LocationEarliestReceipt> findEarliestReceiptByLocation(
            @Param("stockItemId") String stockItemId,
            @Param("locationIds") Collection<UUID> locationIds,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes);

    interface LocationEarliestReceipt {
        UUID getLocationId();

        java.time.Instant getEarliestReceipt();
    }

    /**
     * Maximum number of location ids passed to a single {@code IN} clause.
     */
    int LOCATION_ID_CHUNK_SIZE = 1000;

    @Query("""
                        SELECT e.locationId AS locationId, COALESCE(SUM(e.changeInQuantity), 0) AS quantity
                        FROM InventoryLedgerEntry e
                        WHERE e.locationId IN :locationIds
                          AND e.eventType IN :eventTypes
                        GROUP BY e.locationId
                        """)
    List<LocationQuantity> sumQuantityByLocation(
            @Param("locationIds") Collection<UUID> locationIds,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes);

    @Query("""
                        SELECT e.locationId AS locationId, COALESCE(SUM(e.changeInQuantity), 0) AS quantity
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND e.locationId IN :locationIds
                          AND e.eventType IN :eventTypes
                        GROUP BY e.locationId
                        """)
    List<LocationQuantity> sumQuantityByLocationForSku(
            @Param("stockItemId") String stockItemId,
            @Param("locationIds") Collection<UUID> locationIds,
            @Param("eventTypes") Collection<InventoryLedgerEventType> eventTypes);

    /**
     * Chunk-safe grouped sum of {@code changeInQuantity} per location for the given event types.
     *
     * <p>Locations without matching ledger entries are absent from the result (treat as 0).
     * An empty {@code locationIds} collection short-circuits to an empty map without executing SQL.
     */
    default Map<UUID, Long> sumQuantityByLocationChunked(
            Collection<UUID> locationIds, Collection<InventoryLedgerEventType> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return Map.of();
        }
        return sumChunked(locationIds, chunk -> sumQuantityByLocation(chunk, eventTypes));
    }

    /**
     * Chunk-safe grouped sum of {@code changeInQuantity} per location for a single stock item.
     *
     * <p>Same semantics as {@link #sumQuantityByLocationChunked(Collection, Collection)}.
     */
    default Map<UUID, Long> sumQuantityByLocationForSkuChunked(
            String stockItemId, Collection<UUID> locationIds, Collection<InventoryLedgerEventType> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return Map.of();
        }
        return sumChunked(locationIds, chunk -> sumQuantityByLocationForSku(stockItemId, chunk, eventTypes));
    }

    /**
     * Outstanding allocations per location: sum(ALLOCATION_CREATED) - sum(ALLOCATION_RELEASED).
     *
     * <p>Mirrors the single-location semantics of
     * {@code LocationInventoryInquiryServiceImpl#calculateOutstandingAllocations}. Locations without
     * allocation entries are absent from the result (treat as 0).
     */
    default Map<UUID, Long> calculateOutstandingAllocationsByLocation(Collection<UUID> locationIds) {
        Map<UUID, Long> created =
                sumQuantityByLocationChunked(locationIds, List.of(InventoryLedgerEventType.ALLOCATION_CREATED));
        Map<UUID, Long> released =
                sumQuantityByLocationChunked(locationIds, List.of(InventoryLedgerEventType.ALLOCATION_RELEASED));

        Map<UUID, Long> outstanding = new HashMap<>(created);
        released.forEach((locationId, quantity) -> outstanding.merge(locationId, -quantity, Long::sum));
        return Map.copyOf(outstanding);
    }

    private Map<UUID, Long> sumChunked(
            Collection<UUID> locationIds, Function<List<UUID>, List<LocationQuantity>> query) {
        if (locationIds == null || locationIds.isEmpty()) {
            return Map.of();
        }

        List<UUID> distinctIds = locationIds.stream().distinct().toList();
        Map<UUID, Long> totals = new HashMap<>();
        for (int start = 0; start < distinctIds.size(); start += LOCATION_ID_CHUNK_SIZE) {
            List<UUID> chunk = distinctIds.subList(start, Math.min(start + LOCATION_ID_CHUNK_SIZE, distinctIds.size()));
            for (LocationQuantity row : query.apply(chunk)) {
                totals.merge(row.getLocationId(), row.getQuantity(), Long::sum);
            }
        }
        return Map.copyOf(totals);
    }

    interface LocationQuantity {
        UUID getLocationId();

        long getQuantity();
    }

    /**
     * Ledger-truth aggregation per (stockItemId, locationId) for every key with
     * summary-relevant events (issue #1024, A1). Used by the summary rebuild
     * job and the drift verifier — not by read endpoints.
     */
    @Query("""
                        SELECT e.stockItemId AS stockItemId, e.locationId AS locationId,
                               COALESCE(SUM(CASE WHEN e.eventType IN :onHandTypes THEN e.changeInQuantity ELSE 0 END), 0) AS onHand,
                               COALESCE(SUM(CASE WHEN e.eventType = :allocationCreated THEN e.changeInQuantity
                                                 WHEN e.eventType = :allocationReleased THEN -e.changeInQuantity
                                                 ELSE 0 END), 0) AS allocated,
                               COALESCE(SUM(CASE WHEN e.eventType = :reservationCreated THEN e.changeInQuantity
                                                 WHEN e.eventType = :reservationReleased THEN -e.changeInQuantity
                                                 ELSE 0 END), 0) AS reserved,
                               COALESCE(SUM(CASE WHEN e.eventType = :transferIn THEN -e.changeInQuantity
                                                 ELSE 0 END), 0) AS inTransitArrived
                        FROM InventoryLedgerEntry e
                        WHERE e.eventType IN :summaryTypes
                        GROUP BY e.stockItemId, e.locationId
                        """)
    List<SummaryAggregate> aggregateForStockSummary(
            @Param("onHandTypes") Collection<InventoryLedgerEventType> onHandTypes,
            @Param("allocationCreated") InventoryLedgerEventType allocationCreated,
            @Param("allocationReleased") InventoryLedgerEventType allocationReleased,
            @Param("reservationCreated") InventoryLedgerEventType reservationCreated,
            @Param("reservationReleased") InventoryLedgerEventType reservationReleased,
            @Param("transferIn") InventoryLedgerEventType transferIn,
            @Param("summaryTypes") Collection<InventoryLedgerEventType> summaryTypes);

    interface SummaryAggregate {
        String getStockItemId();

        UUID getLocationId();

        long getOnHand();

        long getAllocated();

        long getReserved();

        /**
         * In-transit contribution of arrivals grouped at this key: {@code Σ -change} of
         * {@code TRANSFER_IN} entries (negative — arrivals drain transit). The outbound
         * (positive) side keys on {@code toLocationId} and comes from
         * {@link #aggregateOutboundInTransit} (odoo-parity C2, #1036).
         */
        long getInTransitArrived();
    }

    /**
     * Outbound in-transit contributions keyed at the DESTINATION location (odoo-parity C2,
     * issue #1036): {@code Σ -change} (positive) of {@code TRANSFER_OUT} entries per
     * (stockItemId, toLocationId). Together with {@code SummaryAggregate#getInTransitArrived}
     * this reconstructs {@code inTransitQty} from ledger shape alone. Rebuild/drift use only.
     */
    @Query("""
                        SELECT e.stockItemId AS stockItemId, e.toLocationId AS locationId,
                               COALESCE(SUM(-e.changeInQuantity), 0) AS inTransit
                        FROM InventoryLedgerEntry e
                        WHERE e.eventType = :transferOut
                          AND e.toLocationId IS NOT NULL
                        GROUP BY e.stockItemId, e.toLocationId
                        """)
    List<OutboundInTransitAggregate> aggregateOutboundInTransit(
            @Param("transferOut") InventoryLedgerEventType transferOut);

    interface OutboundInTransitAggregate {
        String getStockItemId();

        UUID getLocationId();

        long getInTransit();
    }

    /**
     * Per-lot ledger-truth aggregation (odoo-parity E1, issue #1038): the same delta rules as
     * {@link #aggregateForStockSummary} restricted to lot-tagged entries and additionally
     * grouped by {@code lotId}. Mirrors the posting funnel's dual-row rule — lot-tagged entries
     * contribute to BOTH the lot-agnostic aggregation above and these per-lot keys. Rebuild
     * use only.
     */
    @Query("""
                        SELECT e.stockItemId AS stockItemId, e.locationId AS locationId, e.lotId AS lotId,
                               COALESCE(SUM(CASE WHEN e.eventType IN :onHandTypes THEN e.changeInQuantity ELSE 0 END), 0) AS onHand,
                               COALESCE(SUM(CASE WHEN e.eventType = :allocationCreated THEN e.changeInQuantity
                                                 WHEN e.eventType = :allocationReleased THEN -e.changeInQuantity
                                                 ELSE 0 END), 0) AS allocated,
                               COALESCE(SUM(CASE WHEN e.eventType = :reservationCreated THEN e.changeInQuantity
                                                 WHEN e.eventType = :reservationReleased THEN -e.changeInQuantity
                                                 ELSE 0 END), 0) AS reserved,
                               COALESCE(SUM(CASE WHEN e.eventType = :transferIn THEN -e.changeInQuantity
                                                 ELSE 0 END), 0) AS inTransitArrived
                        FROM InventoryLedgerEntry e
                        WHERE e.eventType IN :summaryTypes
                          AND e.lotId IS NOT NULL
                        GROUP BY e.stockItemId, e.locationId, e.lotId
                        """)
    List<LotSummaryAggregate> aggregateForStockSummaryByLot(
            @Param("onHandTypes") Collection<InventoryLedgerEventType> onHandTypes,
            @Param("allocationCreated") InventoryLedgerEventType allocationCreated,
            @Param("allocationReleased") InventoryLedgerEventType allocationReleased,
            @Param("reservationCreated") InventoryLedgerEventType reservationCreated,
            @Param("reservationReleased") InventoryLedgerEventType reservationReleased,
            @Param("transferIn") InventoryLedgerEventType transferIn,
            @Param("summaryTypes") Collection<InventoryLedgerEventType> summaryTypes);

    interface LotSummaryAggregate extends SummaryAggregate {
        UUID getLotId();
    }

    /**
     * Per-lot outbound in-transit contributions keyed at the DESTINATION location (odoo-parity
     * E1, issue #1038; C2 rule): lot-tagged {@code TRANSFER_OUT} entries contribute at their
     * (stockItemId, toLocationId, lotId) key in addition to the lot-agnostic aggregation of
     * {@link #aggregateOutboundInTransit}. Rebuild/drift use only.
     */
    @Query("""
                        SELECT e.stockItemId AS stockItemId, e.toLocationId AS locationId, e.lotId AS lotId,
                               COALESCE(SUM(-e.changeInQuantity), 0) AS inTransit
                        FROM InventoryLedgerEntry e
                        WHERE e.eventType = :transferOut
                          AND e.toLocationId IS NOT NULL
                          AND e.lotId IS NOT NULL
                        GROUP BY e.stockItemId, e.toLocationId, e.lotId
                        """)
    List<LotOutboundInTransitAggregate> aggregateOutboundInTransitByLot(
            @Param("transferOut") InventoryLedgerEventType transferOut);

    interface LotOutboundInTransitAggregate extends OutboundInTransitAggregate {
        UUID getLotId();
    }

    /** Latest summary-relevant entries for one key; pass a page of size 1. Rebuild use only. */
    @Query("""
                        SELECT e
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND ((:locationId IS NULL AND e.locationId IS NULL) OR e.locationId = :locationId)
                          AND e.eventType IN :summaryTypes
                        ORDER BY e.timestamp DESC, e.ledgerEntryId DESC
                        """)
    List<InventoryLedgerEntry> findLatestSummaryEntries(
            @Param("stockItemId") String stockItemId,
            @Param("locationId") UUID locationId,
            @Param("summaryTypes") Collection<InventoryLedgerEventType> summaryTypes,
            Pageable pageable);

    /**
     * Latest summary-relevant entries for one per-lot key (odoo-parity E1, #1038); pass a page
     * of size 1. Only lot-tagged entries touch a per-lot row, hence the {@code lotId} equality
     * filter. Rebuild use only.
     */
    @Query("""
                        SELECT e
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND ((:locationId IS NULL AND e.locationId IS NULL) OR e.locationId = :locationId)
                          AND e.lotId = :lotId
                          AND e.eventType IN :summaryTypes
                        ORDER BY e.timestamp DESC, e.ledgerEntryId DESC
                        """)
    List<InventoryLedgerEntry> findLatestSummaryEntriesForLot(
            @Param("stockItemId") String stockItemId,
            @Param("locationId") UUID locationId,
            @Param("lotId") UUID lotId,
            @Param("summaryTypes") Collection<InventoryLedgerEventType> summaryTypes,
            Pageable pageable);

    /**
     * First non-blank unit of measure recorded for a stock item (oldest entry
     * first); pass a page of size 1. Preserves the pre-#1024 availability
     * response's derived UoM without scanning the full ledger.
     */
    @Query("""
                        SELECT e.unitOfMeasure
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND e.unitOfMeasure IS NOT NULL AND TRIM(e.unitOfMeasure) <> ''
                        ORDER BY e.timestamp ASC, e.ledgerEntryId ASC
                        """)
    List<String> findUnitsOfMeasureByStockItem(@Param("stockItemId") String stockItemId, Pageable pageable);

    // ─── Scrap cost snapshot (odoo-parity D1, #1030; ADR-0048 interim rule) ───

    /**
     * Latest non-null unit costs recorded for one stock item and event type,
     * newest first; pass a page of size 1. Interim scrap cost source: the most
     * recent {@code GOODS_RECEIPT} cost (odoo-parity J1 replaces this).
     */
    @Query("""
                        SELECT e.unitCost
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND e.eventType = :eventType
                          AND e.unitCost IS NOT NULL
                        ORDER BY e.timestamp DESC, e.ledgerEntryId DESC
                        """)
    List<BigDecimal> findLatestUnitCostByStockItemAndEventType(
            @Param("stockItemId") String stockItemId,
            @Param("eventType") InventoryLedgerEventType eventType,
            Pageable pageable);

    /**
     * Latest non-null unit cost of any ledger entry for one stock item, newest
     * first; pass a page of size 1. Fallback for the scrap cost snapshot when
     * the SKU has no costed receipt.
     */
    @Query("""
                        SELECT e.unitCost
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND e.unitCost IS NOT NULL
                        ORDER BY e.timestamp DESC, e.ledgerEntryId DESC
                        """)
    List<BigDecimal> findLatestUnitCostByStockItem(@Param("stockItemId") String stockItemId, Pageable pageable);

    /** Location-scoped variant of {@link #findUnitsOfMeasureByStockItem}. */
    @Query("""
                        SELECT e.unitOfMeasure
                        FROM InventoryLedgerEntry e
                        WHERE e.stockItemId = :stockItemId
                          AND e.locationId = :locationId
                          AND e.unitOfMeasure IS NOT NULL AND TRIM(e.unitOfMeasure) <> ''
                        ORDER BY e.timestamp ASC, e.ledgerEntryId ASC
                        """)
    List<String> findUnitsOfMeasureByStockItemAtLocation(
            @Param("stockItemId") String stockItemId, @Param("locationId") UUID locationId, Pageable pageable);
}
