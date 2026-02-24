package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryAdjustmentRequest;
import com.positivity.inventory.internal.enums.AdjustmentRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link InventoryAdjustmentRequest} entities.
 *
 * Issue: CAP-215 Story #37
 */
@Repository
public interface InventoryAdjustmentRequestRepository extends JpaRepository<InventoryAdjustmentRequest, UUID> {

    List<InventoryAdjustmentRequest> findByProductSkuAndStatus(String productSku, AdjustmentRequestStatus status);
}
