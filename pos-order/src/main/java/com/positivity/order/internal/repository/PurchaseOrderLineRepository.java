package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Purchase-order lines.
 *
 * <p>Deliberately narrower than the pos-inventory repository it replaces. The two aggregate
 * queries that summed open quantity and collected delivery dates were availability-to-promise
 * questions; they belong to the module that answers them, and now run against
 * {@code ext_purchase_order} there (#1333). Reproducing them here would recreate the
 * cross-domain read the split exists to remove.
 */
public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLineEntity, UUID> {

    List<PurchaseOrderLineEntity> findByPurchaseOrder_PurchaseOrderId(UUID purchaseOrderId);
}
