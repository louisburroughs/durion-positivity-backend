package com.positivity.order.internal.repository;

import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderStatusRollup;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Listing goes through {@link JpaSpecificationExecutor}: the four optional filters on
 * {@code GET /v1/orders/purchase-orders} compose into one predicate so that paging and counting
 * happen over the filtered set (#1804). Per-filter derived queries do not extend to four
 * independent optionals, and filtering a fetched page in memory returns short pages with a total
 * that counts rows the caller never gets.
 */
public interface PurchaseOrderRepository
        extends JpaRepository<PurchaseOrderEntity, UUID>, JpaSpecificationExecutor<PurchaseOrderEntity> {

    Optional<PurchaseOrderEntity> findByPoNumber(String poNumber);

    /**
     * Next value of the purchase-order number sequence.
     *
     * @return next sequence value for purchase order number generation
     */
    @Query(value = "SELECT nextval('purchase_order_number_seq')", nativeQuery = true)
    long getNextPurchaseOrderSequence();

    /**
     * Header totals per status over every order: the population a page cannot see (#1798).
     */
    @Query("select new com.positivity.order.internal.dto.purchaseorder.PurchaseOrderStatusRollup("
            + "po.status, count(po), sum(po.grandTotalMinor), sum(po.openBalanceMinor)) "
            + "from PurchaseOrderEntity po group by po.status")
    List<PurchaseOrderStatusRollup> rollupByStatus();

    @Query("select new com.positivity.order.internal.dto.purchaseorder.PurchaseOrderStatusRollup("
            + "po.status, count(po), sum(po.grandTotalMinor), sum(po.openBalanceMinor)) "
            + "from PurchaseOrderEntity po where po.vendorId = :vendorId group by po.status")
    List<PurchaseOrderStatusRollup> rollupByStatusForVendor(@Param("vendorId") UUID vendorId);
}
