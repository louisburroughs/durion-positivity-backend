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
