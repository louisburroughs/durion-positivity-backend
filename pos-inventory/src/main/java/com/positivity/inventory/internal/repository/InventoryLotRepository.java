package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.tenancy.TenantAudited;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Insert-if-absent of a lot master row, run inside the caller's transaction (one connection, no
     * {@code REQUIRES_NEW}). Target-less {@code ON CONFLICT DO NOTHING} arbitrates
     * {@code uq_inventory_lot_sku_lot_number} without raising a violation (no rollback-only mark); on Postgres a
     * concurrent first receipt of the same (stockItemId, lotNumber) waits for the in-flight inserter and the caller's
     * re-read returns the winner's row. Values match the former JPA path: status {@code ACTIVE}, no alert bookkeeping.
     *
     * @return 1 if this call inserted the lot, 0 if it already existed
     */
    @TenantAudited(
            reason = "names the tenant explicitly (the caller's resolved tenant), so the row is the bound tenant's on"
                    + " Postgres and on the H2 slices alike; the policy's WITH CHECK still refuses any other tenant")
    @Modifying
    @Query(value = """
                    INSERT INTO inventory_lot
                        (tenant_id, lot_id, stock_item_id, lot_number, received_at, vendor_id, expiration_date,
                         status, created_at, updated_at)
                    VALUES (:tenantId, :id, :stockItemId, :lotNumber, :now, CAST(:vendorId AS uuid),
                            CAST(:expirationDate AS date), 'ACTIVE', :now, :now)
                    ON CONFLICT DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("stockItemId") String stockItemId,
            @Param("lotNumber") String lotNumber,
            @Param("vendorId") UUID vendorId,
            @Param("expirationDate") LocalDate expirationDate,
            @Param("now") Instant now);
}
