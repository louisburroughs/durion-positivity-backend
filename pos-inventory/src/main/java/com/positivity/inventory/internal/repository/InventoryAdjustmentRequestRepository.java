package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryAdjustmentRequest;
import com.positivity.inventory.internal.enums.AdjustmentRequestStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link InventoryAdjustmentRequest} entities.
 *
 * Issue: CAP-215 Story #37
 */
public interface InventoryAdjustmentRequestRepository extends JpaRepository<InventoryAdjustmentRequest, UUID> {

    List<InventoryAdjustmentRequest> findByProductSkuAndStatus(String productSku, AdjustmentRequestStatus status);
}
