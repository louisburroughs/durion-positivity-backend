package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.PurchaseOrderLineEntity;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLineEntity, UUID> {

    /**
     * Open (un-received) purchase-order supply for one SKU (odoo-parity A2, issue #1028):
     * sum of {@code openQuantityDecimal} over lines of purchase orders in the given statuses,
     * optionally restricted to one ship-to site and to a delivery-date horizon.
     *
     * <p>The expected delivery date lives on the purchase-order header, so the horizon bound
     * applies at header granularity. With {@code boundByExpectedDelivery = true}, orders with a
     * {@code NULL} expected delivery date are excluded (undated supply cannot be promised by a
     * date); with {@code false} they are included and {@code horizonDate} is ignored.
     */
    @Query("""
                        SELECT COALESCE(SUM(l.openQuantityDecimal), 0)
                        FROM PurchaseOrderLineEntity l
                        WHERE l.skuId = :skuId
                          AND l.purchaseOrder.status IN :statuses
                          AND (:siteId IS NULL OR l.purchaseOrder.shipToLocationId = :siteId)
                          AND (:boundByExpectedDelivery = FALSE
                               OR (l.purchaseOrder.expectedDeliveryDate IS NOT NULL
                                   AND l.purchaseOrder.expectedDeliveryDate <= :horizonDate))
                        """)
    BigDecimal sumOpenQuantityForSku(
            @Param("skuId") UUID skuId,
            @Param("statuses") Collection<PurchaseOrderStatus> statuses,
            @Param("siteId") UUID siteId,
            @Param("boundByExpectedDelivery") boolean boundByExpectedDelivery,
            @Param("horizonDate") LocalDate horizonDate);

    /**
     * Distinct expected delivery dates of open PO supply for one SKU within a horizon
     * (odoo-parity F3, issue #1041): the candidate cutoff dates at which the
     * horizon-bounded forecast projection can change — used by the stock-out deadline
     * approximation. Undated orders are excluded, matching the bounded variant of
     * {@link #sumOpenQuantityForSku}.
     */
    @Query("""
                        SELECT DISTINCT l.purchaseOrder.expectedDeliveryDate
                        FROM PurchaseOrderLineEntity l
                        WHERE l.skuId = :skuId
                          AND l.purchaseOrder.status IN :statuses
                          AND (:siteId IS NULL OR l.purchaseOrder.shipToLocationId = :siteId)
                          AND l.purchaseOrder.expectedDeliveryDate IS NOT NULL
                          AND l.purchaseOrder.expectedDeliveryDate <= :horizonDate
                        """)
    List<LocalDate> findDistinctExpectedDeliveryDatesForSku(
            @Param("skuId") UUID skuId,
            @Param("statuses") Collection<PurchaseOrderStatus> statuses,
            @Param("siteId") UUID siteId,
            @Param("horizonDate") LocalDate horizonDate);
}
