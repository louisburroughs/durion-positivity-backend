package com.positivity.inventory.internal.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.inventory.internal.entity.CycleCountPlan;

@Repository
public interface CycleCountPlanRepository extends JpaRepository<CycleCountPlan, UUID> {
}
