package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.TechnicianAssignment;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for managing technician assignments to workorders.
 */
public interface TechnicianAssignmentRepository extends JpaRepository<TechnicianAssignment, Long> {

    /**
     * Find the current assignment for a workorder.
     *
     * @param workorderId the workorder ID
     * @return optional containing the current assignment if one exists
     */
    @NonNull
    Optional<TechnicianAssignment> findByWorkorder_IdAndCurrentTrue(@NonNull UUID workorderId);

    /**
     * Find all assignments for a workorder ordered by most recent first.
     *
     * @param workorderId the workorder ID
     * @return list of assignments ordered by assignedAt descending
     */
    @NonNull
    List<TechnicianAssignment> findByWorkorder_IdOrderByAssignedAtDesc(@NonNull UUID workorderId);

    @NonNull
    List<TechnicianAssignment> findByWorkorder_IdInAndCurrentTrue(@NonNull Set<UUID> workorderIds);

    /** Ids of a workorder's current technician, read without loading either entity. */
    interface CurrentTechnician {
        UUID getWorkorderId();

        UUID getTechnicianId();
    }

    /**
     * The current technician of each listed workorder, as ids only.
     *
     * <p>The dispatch dashboard needs just the two ids. Selecting {@code a.workorder.id} reads the
     * foreign key column, so no LAZY {@code workorder} proxy is traversed after the query returns —
     * with open-in-view off that proxy is detached — and no per-row workorder load can follow.
     */
    @Query("SELECT a.workorder.id AS workorderId, a.technicianId AS technicianId FROM TechnicianAssignment a"
            + " WHERE a.workorder.id IN :workorderIds AND a.current = TRUE")
    @NonNull
    List<CurrentTechnician> findCurrentTechnicians(@Param("workorderIds") @NonNull Set<UUID> workorderIds);

    /**
     * The current assignment, locked for update, for the operations that replace or end it (#1985).
     *
     * <p>Release and reassign both read the current row and then close it. Unlocked, a release that
     * read T1 can be overtaken by a reassignment that closes T1 and makes T2 current, and the
     * release then closes a row that is already closed and reports success — leaving T2 holding a
     * workorder its caller believes is now unassigned. The partial unique index cannot catch that:
     * the two transactions never insert a conflicting current row, they disagree about which row
     * they were acting on. A row lock makes the second reader wait and see the truth.
     *
     * <p>Assignment does not need this: there is no current row to lock, and two racing assigns are
     * decided by {@code technician_assignment_one_current_uniq}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM TechnicianAssignment a WHERE a.workorder.id = :workorderId AND a.current = TRUE")
    @NonNull
    Optional<TechnicianAssignment> findCurrentForUpdate(@Param("workorderId") @NonNull UUID workorderId);
}
