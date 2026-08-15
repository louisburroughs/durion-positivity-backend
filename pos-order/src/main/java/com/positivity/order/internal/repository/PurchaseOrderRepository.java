package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, UUID> {

    Optional<PurchaseOrderEntity> findByPoNumber(String poNumber);

    /**
     * Next value of the purchase-order number sequence.
     *
     * @return next sequence value for purchase order number generation
     */
    @Query(value = "SELECT nextval('purchase_order_number_seq')", nativeQuery = true)
    long getNextPurchaseOrderSequence();

    List<PurchaseOrderEntity> findByVendorIdAndStatus(UUID vendorId, PurchaseOrderStatus status);

    Page<PurchaseOrderEntity> findByVendorIdAndStatus(UUID vendorId, PurchaseOrderStatus status, Pageable pageable);

    Page<PurchaseOrderEntity> findByVendorId(UUID vendorId, Pageable pageable);

    Page<PurchaseOrderEntity> findByStatus(PurchaseOrderStatus status, Pageable pageable);

    Page<PurchaseOrderEntity> findAll(Pageable pageable);
}
