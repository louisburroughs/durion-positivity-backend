package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventorySerialUnit;
import com.positivity.inventory.internal.enums.InventorySerialStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link InventorySerialUnit} master rows (odoo-parity E4, issue #1050).
 *
 * <p>Writes go exclusively through the ledger posting funnel's serial capture/consumption path
 * ({@code SerialUnitPostingService}); list filtering (stockItemId/status/serialNumber) goes
 * through the {@link JpaSpecificationExecutor}.
 */
public interface InventorySerialUnitRepository
        extends JpaRepository<InventorySerialUnit, UUID>, JpaSpecificationExecutor<InventorySerialUnit> {

    Optional<InventorySerialUnit> findByStockItemIdAndSerialNumber(String stockItemId, String serialNumber);

    long countByStockItemIdAndStatus(String stockItemId, InventorySerialStatus status);

    /**
     * Serial-derived on-hand rebuild (issue #1050 verifier): count of {@code IN_STOCK} units per
     * (stockItemId, locationId). Only these count toward serial on-hand; the verifier reconciles
     * this against the ledger/summary balance.
     */
    @Query("""
            SELECT u.stockItemId AS stockItemId, u.locationId AS locationId, COUNT(u) AS inStockCount
            FROM InventorySerialUnit u
            WHERE u.status = :status
            GROUP BY u.stockItemId, u.locationId
            """)
    List<SerialOnHandRow> serialOnHandByStockItemAndLocation(@Param("status") InventorySerialStatus status);

    /** One (stockItemId, locationId) serial-derived on-hand row for the verifier. */
    interface SerialOnHandRow {
        String getStockItemId();

        UUID getLocationId();

        long getInStockCount();
    }
}
