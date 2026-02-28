package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.AllocationAuditEntity;
import com.positivity.inventory.internal.enums.AllocationAuditReasonCode;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AllocationAuditRepository extends JpaRepository<AllocationAuditEntity, UUID> {
    List<AllocationAuditEntity> findByStockItemId(String stockItemId);

    List<AllocationAuditEntity> findByReasonCode(AllocationAuditReasonCode reasonCode);
}
