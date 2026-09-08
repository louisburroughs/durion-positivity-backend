package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CycleCountPlan;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CycleCountPlanRepository extends JpaRepository<CycleCountPlan, UUID> {

    /**
     * Row-locks one plan for the duration of the transaction. Task generation
     * takes this lock so concurrent generation requests for the same plan
     * serialize: the second waits, then sees the first's tasks and skips them,
     * instead of racing the (plan, bin, SKU) unique constraint and rolling
     * back its whole pass.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM CycleCountPlan p WHERE p.planId = :planId")
    Optional<CycleCountPlan> findWithLockByPlanId(@Param("planId") @NonNull UUID planId);

    @Query("""
            SELECT p FROM CycleCountPlan p
            WHERE (:locationId IS NULL OR p.locationId = :locationId)
              AND (:status IS NULL OR p.status = :status)
            """)
    Page<CycleCountPlan> findByOptionalFilters(
            @Param("locationId") UUID locationId, @Param("status") CycleCountPlanStatus status, Pageable pageable);

    /**
     * {@link #findByOptionalFilters} narrowed to the caller's reach (ADR-0061 §3, #1872): plans at
     * a reachable site, or at a storage location replicated under one. Never called with an empty
     * set — an empty reach is an empty page decided before the query.
     */
    @Query("""
            SELECT p FROM CycleCountPlan p
            WHERE (p.locationId IN :locationIds
                   OR p.locationId IN (SELECT s.storageLocationId FROM ExtStorageLocationReplica s
                                       WHERE s.siteId IN :locationIds))
              AND (:status IS NULL OR p.status = :status)
            """)
    Page<CycleCountPlan> findByOptionalFiltersWithinLocations(
            @Param("locationIds") Collection<UUID> locationIds,
            @Param("status") CycleCountPlanStatus status,
            Pageable pageable);

    /** Exactly-once guard for schedule-created plans (odoo-parity I1, #1031). */
    boolean existsByScheduleIdAndDueDate(UUID scheduleId, LocalDate dueDate);
}
