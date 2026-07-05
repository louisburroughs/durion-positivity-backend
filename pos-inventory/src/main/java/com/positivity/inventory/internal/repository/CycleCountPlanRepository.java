package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CycleCountPlan;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CycleCountPlanRepository extends JpaRepository<CycleCountPlan, UUID> {

    @Query("""
            SELECT p FROM CycleCountPlan p
            WHERE (:locationId IS NULL OR p.locationId = :locationId)
              AND (:status IS NULL OR p.status = :status)
            """)
    Page<CycleCountPlan> findByOptionalFilters(
            @Param("locationId") UUID locationId, @Param("status") CycleCountPlanStatus status, Pageable pageable);
}
