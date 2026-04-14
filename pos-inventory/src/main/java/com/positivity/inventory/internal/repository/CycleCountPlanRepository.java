package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CycleCountPlan;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CycleCountPlanRepository extends JpaRepository<CycleCountPlan, UUID> {}
