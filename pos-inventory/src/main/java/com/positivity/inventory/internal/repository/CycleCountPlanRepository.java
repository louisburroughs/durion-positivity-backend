package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CycleCountPlan;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CycleCountPlanRepository extends JpaRepository<CycleCountPlan, UUID> {

    List<CycleCountPlan> findByLocationIdOrderByCreatedAtDesc(UUID locationId);

    List<CycleCountPlan> findByStatusOrderByCreatedAtDesc(CycleCountPlanStatus status);

    List<CycleCountPlan> findByLocationIdAndStatusOrderByCreatedAtDesc(UUID locationId, CycleCountPlanStatus status);
}
