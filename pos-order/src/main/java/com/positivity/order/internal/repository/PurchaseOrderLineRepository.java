package com.positivity.order.internal.repository;

import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRollup;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Line totals per parent-order status over every line. {@code openQuantityDecimal} is the
     * outstanding balance pos-inventory maintains on the order's row; summing it is the only way to
     * answer "how many units are still on order" without paging through every order (#1798).
     */
    @Query("select new com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRollup("
            + "po.status, count(l), sum(l.quantityDecimal), sum(l.openQuantityDecimal)) "
            + "from PurchaseOrderLineEntity l join l.purchaseOrder po group by po.status")
    List<PurchaseOrderLineRollup> rollupByStatus();

    @Query("select new com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRollup("
            + "po.status, count(l), sum(l.quantityDecimal), sum(l.openQuantityDecimal)) "
            + "from PurchaseOrderLineEntity l join l.purchaseOrder po where po.vendorId = :vendorId "
            + "group by po.status")
    List<PurchaseOrderLineRollup> rollupByStatusForVendor(@Param("vendorId") UUID vendorId);
}
