package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtPurchaseOrderLineReplica;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Supply queries over the projected purchase order.
 *
 * <p>These are ports of the queries that ran against the local aggregate, kept semantically
 * identical on purpose: availability-to-promise is computed from them, and a subtle change in
 * filtering is not a refactor, it is a different promise to a customer. The parity test compares the
 * two implementations directly for that reason.
 *
 * <p>The projection stores the order's status as a string rather than the local enum, because the
 * owner's lifecycle is the owner's to name. Callers pass
 * {@code PurchaseOrderUpdatedV1.OPEN_SUPPLY_STATUSES} rather than assembling the list themselves.
 */
public interface ExtPurchaseOrderLineRepository extends JpaRepository<ExtPurchaseOrderLineReplica, UUID> {

    /**
     * Outstanding supply for one SKU: the sum of open quantities over projected orders in the given
     * statuses, optionally restricted to one ship-to site and to a delivery-date horizon.
     *
     * <p>The expected delivery date lives on the order header, so the horizon bound applies at header
     * granularity. With {@code boundByExpectedDelivery = true}, orders with a NULL expected delivery
     * date are excluded — undated supply cannot be promised by a date; with {@code false} they are
     * included and {@code horizonDate} is ignored. That exclusion is load-bearing and is the first
     * thing to check if ATP parity ever drifts.
     */
    @Query("""
      SELECT COALESCE(SUM(l.openQuantity), 0)
      FROM ExtPurchaseOrderLineReplica l, ExtPurchaseOrderReplica o
      WHERE o.purchaseOrderId = l.purchaseOrderId
        AND l.skuId = :skuId
        AND o.status IN :statuses
        AND (:siteId IS NULL OR o.shipToLocationId = :siteId)
        AND (:boundByExpectedDelivery = FALSE
             OR (o.expectedDeliveryDate IS NOT NULL
                 AND o.expectedDeliveryDate <= :horizonDate))
      """)
    BigDecimal sumOpenQuantityForSku(
            @Param("skuId") UUID skuId,
            @Param("statuses") Collection<String> statuses,
            @Param("siteId") UUID siteId,
            @Param("boundByExpectedDelivery") boolean boundByExpectedDelivery,
            @Param("horizonDate") LocalDate horizonDate);

    /**
     * Distinct expected delivery dates of outstanding supply for one SKU within a horizon: the
     * candidate cutoffs at which a horizon-bounded forecast can change. Undated orders are excluded,
     * matching the bounded variant above.
     */
    @Query("""
      SELECT DISTINCT o.expectedDeliveryDate
      FROM ExtPurchaseOrderLineReplica l, ExtPurchaseOrderReplica o
      WHERE o.purchaseOrderId = l.purchaseOrderId
        AND l.skuId = :skuId
        AND o.status IN :statuses
        AND (:siteId IS NULL OR o.shipToLocationId = :siteId)
        AND o.expectedDeliveryDate IS NOT NULL
        AND o.expectedDeliveryDate <= :horizonDate
      """)
    List<LocalDate> findDistinctExpectedDeliveryDatesForSku(
            @Param("skuId") UUID skuId,
            @Param("statuses") Collection<String> statuses,
            @Param("siteId") UUID siteId,
            @Param("horizonDate") LocalDate horizonDate);

    /** Lines of one projected order, for the consumer's replace-on-apply. */
    List<ExtPurchaseOrderLineReplica> findByPurchaseOrderId(UUID purchaseOrderId);

    void deleteByPurchaseOrderId(UUID purchaseOrderId);
}
