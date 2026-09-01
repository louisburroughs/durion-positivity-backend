package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkorderStateTransitionRepository extends JpaRepository<WorkorderStateTransition, UUID> {
    @NonNull
    List<WorkorderStateTransition> findByWorkorder_Id(@NonNull UUID workorderId);

    @NonNull
    List<WorkorderStateTransition> findByWorkorder_IdOrderByTransitionedAtDesc(@NonNull UUID workorderId);

    /**
     * Chronological transitions for one workorder (E7, #1595) — used by the {@code woId}-only mode
     * of {@code GET /v1/workorders/status-transitions}, oldest first, unlike the desc history finder
     * above which backs the workorder-detail view.
     */
    @NonNull
    List<WorkorderStateTransition> findByWorkorder_IdOrderByTransitionedAtAsc(@NonNull UUID workorderId);

    /**
     * Date-range finder (E7, #1595) backing {@code GET /v1/workorders/status-transitions} and, in
     * turn, the E6/E5 analytics endpoints. {@code fromStatus}/{@code toStatus} are optional filters
     * — {@code null} matches any status. {@code end} is exclusive (half-open range); callers pass
     * {@code endDate.plusDays(1).atStartOfDay(UTC)} to make an inclusive calendar-date end. Ordered
     * oldest-first. Callers pass a {@link Pageable} sized {@code limit + 1} to detect truncation
     * without a second count query (sort is expressed in the JPQL, not the {@code Pageable}).
     */
    @NonNull
    @Query("SELECT t FROM WorkorderStateTransition t "
            + "WHERE t.transitionedAt >= :start AND t.transitionedAt < :end "
            + "AND (:fromStatus IS NULL OR t.fromStatus = :fromStatus) "
            + "AND (:toStatus IS NULL OR t.toStatus = :toStatus) "
            + "ORDER BY t.transitionedAt ASC")
    List<WorkorderStateTransition> findByTransitionedAtRangeAndStatuses(
            @Param("start") @NonNull Instant start,
            @Param("end") @NonNull Instant end,
            @Param("fromStatus") @Nullable WorkorderStatus fromStatus,
            @Param("toStatus") @Nullable WorkorderStatus toStatus,
            @NonNull Pageable pageable);
}
