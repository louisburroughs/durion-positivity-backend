package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryLot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository for {@link InventoryLot} master rows (odoo-parity E1, issue #1038).
 *
 * <p>Writes go exclusively through the inbound lot-capture path
 * ({@code InventoryLotCaptureService} find-or-create); list filtering
 * (stockItemId/status/lotNumber) goes through the {@link JpaSpecificationExecutor}.
 */
public interface InventoryLotRepository
        extends JpaRepository<InventoryLot, UUID>, JpaSpecificationExecutor<InventoryLot> {

    Optional<InventoryLot> findByStockItemIdAndLotNumber(String stockItemId, String lotNumber);
}
