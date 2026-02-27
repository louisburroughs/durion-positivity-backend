package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.PurchaseOrderEntity;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, UUID> {

    Optional<PurchaseOrderEntity> findByPoNumber(String poNumber);

    List<PurchaseOrderEntity> findByVendorIdAndStatus(UUID vendorId, PurchaseOrderStatus status);

    Page<PurchaseOrderEntity> findAllByStatus(PurchaseOrderStatus status, Pageable pageable);
}
