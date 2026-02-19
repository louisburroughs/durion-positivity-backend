package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ItemCostAuditEntity;
import com.positivity.catalog.internal.enums.CostType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemCostAuditRepository extends JpaRepository<ItemCostAuditEntity, UUID> {

    List<ItemCostAuditEntity> findByItemIdOrderByTimestampDesc(UUID itemId);

    List<ItemCostAuditEntity> findByItemIdAndCostTypeChangedAndTimestampBetweenOrderByTimestampDesc(
            UUID itemId,
            CostType costTypeChanged,
            Instant start,
            Instant end);
}
