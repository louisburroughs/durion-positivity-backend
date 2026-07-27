package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.TransferOrder;
import com.positivity.inventory.internal.enums.TransferOrderStatus;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link TransferOrder} aggregates (odoo-parity C1, issue #1035). Lines are
 * cascade-managed through the aggregate root; there is no standalone line repository.
 */
public interface TransferOrderRepository
        extends JpaRepository<TransferOrder, UUID>, JpaSpecificationExecutor<TransferOrder> {

    /**
     * Sum of in-transit quantity ({@code dispatchedQty − receivedQty}) inbound to a
     * destination site for one SKU across open transfers (odoo-parity C2, issue #1036).
     * Feeds the destination's expected-supply {@code incomingQty}; {@code siteId = null}
     * sums across all destinations.
     */
    @Query("""
                        SELECT COALESCE(SUM(l.dispatchedQty - l.receivedQty), 0)
                        FROM TransferOrderLine l
                        WHERE l.sku = :sku
                          AND l.transferOrder.status IN :statuses
                          AND (:siteId IS NULL OR l.transferOrder.destinationLocationId = :siteId)
                        """)
    long sumInboundInTransitForSku(
            @Param("sku") String sku,
            @Param("statuses") Collection<TransferOrderStatus> statuses,
            @Param("siteId") UUID siteId);

    /**
     * Re-sourcing idempotency guard (odoo-parity F5, issue #1045): true when an OPEN source
     * transfer (a DRAFT/APPROVED/DISPATCHED/PARTIALLY_RECEIVED order the given statuses
     * enumerate) already carries a line for {@code sku} inbound to {@code destinationSiteId}.
     * A DRAFT transfer is not yet in-transit (so the forecast does not net it), so this
     * explicit guard prevents the scan from re-sourcing the same need on the next pass.
     */
    @Query("""
                        SELECT COUNT(l) > 0
                        FROM TransferOrderLine l
                        WHERE l.sku = :sku
                          AND l.transferOrder.destinationLocationId = :destinationSiteId
                          AND l.transferOrder.status IN :statuses
                        """)
    boolean existsOpenSourceTransferForSku(
            @Param("destinationSiteId") UUID destinationSiteId,
            @Param("sku") String sku,
            @Param("statuses") Collection<TransferOrderStatus> statuses);
}
