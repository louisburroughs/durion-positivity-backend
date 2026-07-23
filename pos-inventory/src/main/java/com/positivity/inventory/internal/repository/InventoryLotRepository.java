package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * ACTIVE lots whose expiration date has passed ({@code expirationDate < today}) and whose
     * {@code EXPIRED} alert has not yet been emitted (odoo-parity E3, issue #1047). The daily
     * {@code LotExpiryScheduler} walks these to raise a single {@code EXPIRED} alert per lot.
     */
    @Query("""
            SELECT l FROM InventoryLot l
            WHERE l.status = :status
              AND l.expirationDate IS NOT NULL
              AND l.expirationDate < :today
              AND l.expiredAlertedAt IS NULL
            """)
    List<InventoryLot> findNewlyExpired(@Param("status") InventoryLotStatus status, @Param("today") LocalDate today);

    /**
     * ACTIVE lots that have entered their alert window ({@code alertDate <= today}) but are not
     * yet expired ({@code expirationDate >= today}) and whose {@code EXPIRING} alert has not yet
     * been emitted (odoo-parity E3, issue #1047).
     */
    @Query("""
            SELECT l FROM InventoryLot l
            WHERE l.status = :status
              AND l.alertDate IS NOT NULL
              AND l.alertDate <= :today
              AND l.expirationDate IS NOT NULL
              AND l.expirationDate >= :today
              AND l.expiringAlertedAt IS NULL
            """)
    List<InventoryLot> findEnteringAlertWindow(
            @Param("status") InventoryLotStatus status, @Param("today") LocalDate today);
}
