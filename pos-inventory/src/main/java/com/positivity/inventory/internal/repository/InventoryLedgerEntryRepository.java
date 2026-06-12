package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link InventoryLedgerEntry} entities.
 */
@Repository
public interface InventoryLedgerEntryRepository
        extends JpaRepository<InventoryLedgerEntry, UUID>, JpaSpecificationExecutor<InventoryLedgerEntry> {

    List<InventoryLedgerEntry> findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
            String stockItemId, InventoryLedgerEventType eventType, String notesFragment);

    List<InventoryLedgerEntry> findByStockItemIdOrderByTimestampDesc(String stockItemId);

    List<InventoryLedgerEntry> findByStockItemIdOrderByTimestampAsc(String stockItemId);

    List<InventoryLedgerEntry> findByStockItemIdAndLocationIdOrderByTimestampAsc(String stockItemId, UUID locationId);

    List<InventoryLedgerEntry> findByLocationIdAndEventTypeIn(
            UUID locationId, Collection<InventoryLedgerEventType> eventTypes);

    Optional<InventoryLedgerEntry> findByAdjustmentId(UUID adjustmentId);

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
}
