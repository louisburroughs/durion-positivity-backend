package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.AsnLineEntity;
import com.positivity.inventory.internal.enums.AsnStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AsnLineRepository extends JpaRepository<AsnLineEntity, UUID> {

    /**
     * Un-received ASN remainder for one SKU (odoo-parity A2, issue #1028): sum of
     * {@code quantityShipped - quantityReceived} (per line, floored at zero so over-received
     * lines never subtract) over lines of ASNs in the given statuses, optionally restricted to
     * the owning purchase order's ship-to site and to an arrival-date horizon.
     *
     * <p>The expected arrival date lives on the ASN header, so the horizon bound applies at
     * header granularity. With {@code boundByExpectedArrival = true}, ASNs with a {@code NULL}
     * expected arrival date are excluded (undated supply cannot be promised by a date); with
     * {@code false} they are included and {@code horizonDate} is ignored.
     */
    @Query("""
                        SELECT COALESCE(SUM(CASE WHEN al.quantityShipped - COALESCE(al.quantityReceived, 0) > 0
                                                 THEN al.quantityShipped - COALESCE(al.quantityReceived, 0)
                                                 ELSE 0 END), 0)
                        FROM AsnLineEntity al
                        WHERE al.sku = :sku
                          AND al.asn.status IN :statuses
                          AND (:siteId IS NULL OR al.purchaseOrder.shipToLocationId = :siteId)
                          AND (:boundByExpectedArrival = FALSE
                               OR (al.asn.expectedArrivalDate IS NOT NULL
                                   AND al.asn.expectedArrivalDate <= :horizonDate))
                        """)
    BigDecimal sumUnreceivedRemainderForSku(
            @Param("sku") String sku,
            @Param("statuses") Collection<AsnStatus> statuses,
            @Param("siteId") UUID siteId,
            @Param("boundByExpectedArrival") boolean boundByExpectedArrival,
            @Param("horizonDate") LocalDate horizonDate);

    /**
     * Distinct expected arrival dates of open ASN supply for one SKU within a horizon
     * (odoo-parity F3, issue #1041): candidate cutoff dates for the stock-out deadline
     * approximation. Undated ASNs are excluded, matching the bounded variant of
     * {@link #sumUnreceivedRemainderForSku}.
     */
    @Query("""
                        SELECT DISTINCT al.asn.expectedArrivalDate
                        FROM AsnLineEntity al
                        WHERE al.sku = :sku
                          AND al.asn.status IN :statuses
                          AND (:siteId IS NULL OR al.purchaseOrder.shipToLocationId = :siteId)
                          AND al.asn.expectedArrivalDate IS NOT NULL
                          AND al.asn.expectedArrivalDate <= :horizonDate
                        """)
    List<LocalDate> findDistinctExpectedArrivalDatesForSku(
            @Param("sku") String sku,
            @Param("statuses") Collection<AsnStatus> statuses,
            @Param("siteId") UUID siteId,
            @Param("horizonDate") LocalDate horizonDate);
}
