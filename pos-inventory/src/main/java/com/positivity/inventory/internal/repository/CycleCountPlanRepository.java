package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CycleCountPlan;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CycleCountPlanRepository extends JpaRepository<CycleCountPlan, UUID> {}
